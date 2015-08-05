package com.github.tonybaines.watcher

import groovy.json.JsonOutput

import javax.management.MBeanServerConnection
import javax.management.ObjectName
import javax.management.remote.JMXConnectorFactory as JmxFactory
import javax.management.remote.JMXServiceURL as JmxUrl

import static groovyx.gpars.GParsPool.withPool

class MetricsWatcher {
  private MBeanServerConnection server
  private static final JMX_NAME_TO_JSON = [
    "Max"              : 'max',
    "Min"              : 'min',
    "50thPercentile"   : 'p50',
    "75thPercentile"   : 'p75',
    "95thPercentile"   : 'p95',
    "98thPercentile"   : 'p98',
    "99thPercentile"   : 'p99',
    "999thPercentile"  : 'p999',
    "Mean"             : 'mean',
    "StdDev"           : 'stddev',
    "DurationUnit"     : 'duration_units',
    "Count"            : 'count',
    "FifteenMinuteRate": 'm15_rate',
    "FiveMinuteRate"   : 'm5_rate',
    "OneMinuteRate"    : 'm1_rate',
    "MeanRate"         : 'mean_rate',
    "RateUnit"         : 'rate_units',
    "Value"            : 'value'
  ]

  private static final JMX_NAME_FOR_JSON_METERS = [
    "Count"            : 'count',
    "FifteenMinuteRate": 'm15_rate',
    "FiveMinuteRate"   : 'm5_rate',
    "OneMinuteRate"    : 'm1_rate',
    "MeanRate"         : 'mean_rate',
    "RateUnit"         : 'units',
    "Value"            : 'value'
  ]

  MetricsWatcher(WatcherConfig config) {
    def serverUrl = "service:jmx:rmi:///jndi/rmi://${config.server.host}:${config.server.port}/jmxrmi"
    server = JmxFactory.connect(new JmxUrl(serverUrl)).MBeanServerConnection
  }

  def toJson(objectNameQuery) {
    def beanNames = server.queryNames(new ObjectName(objectNameQuery), null)

    // Do the lookup in parallel
    def timerData = []
    def gaugeData = []
    def counterData = []
    def histogramData = []
    def meterData = []
    withPool {
      def mbeans = beanNames.collectParallel { ObjectName on ->
        new GroovyMBean(server, on)
      }

      def findBeansOfType = { type -> mbeans.grepParallel { it.info().className.endsWith(type) } }
      def asDataMap = { GroovyMBean bean ->
        def name = bean.name().canonicalName
        [name, mapFor(bean)]
      }

      timerData = findBeansOfType('JmxTimer').collectParallel(asDataMap)
      gaugeData = findBeansOfType('JmxGauge').collectParallel(asDataMap)
      counterData = findBeansOfType('JmxCounter').collectParallel(asDataMap)
      histogramData = findBeansOfType('JmxHistogram').collectParallel(asDataMap)
      meterData = findBeansOfType('JmxMeter')
        .collectParallel{ [it.name().canonicalName, mapFor(it, {x -> JMX_NAME_FOR_JSON_METERS[x]})] }

    }
    // Translate the results into a Map
    JsonOutput.toJson([
      "version"   : "3.0.0",
      "gauges"    : gaugeData.collectEntries { it },
      "counters"  : counterData.collectEntries{it},
      "histograms": histogramData.collectEntries{it},
      "meters"    : meterData.collectEntries{it},
      'timers'    : timerData.collectEntries { it }
    ])
  }

  Map mapFor(GroovyMBean bean, translator={x -> JMX_NAME_TO_JSON[x]}) {
    bean.listAttributeNames().collect { name ->
      [translator(name), bean.getProperty(name)]
    }
    .grep{ name,map -> name != null}
    .collectEntries{it}
  }
}
