package com.github.tonybaines.watcher

interface WatcherConfig {
  JmxServer getServer()

  interface JmxServer {
    String getHost()
    Integer getPort()
  }
}