package com.github.tamales

import java.io.File

import akka.ConfigurationException
import com.typesafe.config.ConfigFactory

object ActorConfig {
  private lazy val home = new File(System.getProperty("user.home"), ".tamales")
  private lazy val config = {
    val default = ConfigFactory.load()
    val user = {
      val file = new File(home, "configuration.conf")
      if (file.exists()) {
        ConfigFactory.parseFile(file)
      } else {
        throw new ConfigurationException(s"No configuration found in `$file`.")
      }
    }
    user.withFallback(default)
  }

  config.checkValid(ConfigFactory.defaultReference())
}
trait ActorConfig {

  def config = ActorConfig.config

  def isConfigured(path:String) = config.hasPath(path)

}
