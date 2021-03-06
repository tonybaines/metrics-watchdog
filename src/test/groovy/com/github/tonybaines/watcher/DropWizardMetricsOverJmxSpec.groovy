package com.github.tonybaines.watcher

import com.codahale.metrics.Gauge
import com.codahale.metrics.JmxReporter
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.ObjectNameFactory
import com.codahale.metrics.jvm.ClassLoadingGaugeSet
import com.codahale.metrics.jvm.GarbageCollectorMetricSet
import com.codahale.metrics.jvm.MemoryUsageGaugeSet
import com.codahale.metrics.jvm.ThreadStatesGaugeSet
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification

import javax.management.ObjectName
import java.util.concurrent.TimeUnit

class DropWizardMetricsOverJmxSpec extends Specification {
  static def RANDOM = Random.newInstance()
  static WatcherConfig CONFIG = new WatcherConfig() {
    WatcherConfig.JmxServer getServer() {
      new WatcherConfig.JmxServer() {
        String getHost() { "localhost" }

        Integer getPort() { 9199 }
      }
    }
  }


  def "Converting MBeans to JSON"() {
    given:
    MetricRegistry metrics = new MetricRegistry()

    JmxReporter.forRegistry(metrics)
      .inDomain('test')
      .createsObjectNamesWith(new ObjectNameFactory() {
        ObjectName createName(String type, String domain, String name) {
          return new ObjectName(domain, ['type': type, 'name': name] as Hashtable)
        }
      })
      .convertDurationsTo(TimeUnit.SECONDS)
      .build().start()


    (1..3).each {
      metrics.timer("my-test-timer-$it").update(RANDOM.nextInt(100), TimeUnit.SECONDS)
      metrics.counter("my-test-counter-$it").inc(RANDOM.nextInt(100))
      metrics.meter("my-test-meter-$it").mark(RANDOM.nextInt(100))
      metrics.histogram("my-test-histogram-$it").update(RANDOM.nextInt(100))
      metrics.register("my-test-gauge-$it", new Gauge<Integer>() {
        Integer getValue() {
          return RANDOM.nextInt(100)
        }
      })
    }

    sleep(1000)

    def watcher = new MetricsWatcher(CONFIG)

    when:
    def resultJson = watcher.toJson("test:*")
    println JsonOutput.prettyPrint(resultJson)
    def result = new JsonSlurper().parseText(resultJson)

    then:
    result.timers.'test:name=my-test-timer-3,type=timers'.'mean_rate' > 0
    result.meters.'test:name=my-test-meter-3,type=meters'.'units' == 'events/second'

  }

  def "Converting JVM metrics to JSON"() {
    given:
    MetricRegistry jvmMetrics = new MetricRegistry()
    JmxReporter.forRegistry(jvmMetrics).inDomain('jvm').build().start()
    jvmMetrics.registerAll('classloader', new ClassLoadingGaugeSet())
    jvmMetrics.registerAll('gc', new GarbageCollectorMetricSet())
    jvmMetrics.registerAll('mem', new MemoryUsageGaugeSet())
    jvmMetrics.registerAll('thread', new ThreadStatesGaugeSet())

    sleep(1000)

    def watcher = new MetricsWatcher(CONFIG)

    when:
    def resultJson = watcher.toJson("jvm:*")
    println JsonOutput.prettyPrint(resultJson)
    def result = new JsonSlurper().parseText(resultJson)

    then:
    result.gauges.'jvm:name=mem.non-heap.used'.'value' > 0
  }

}