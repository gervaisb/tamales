package com.github.tamales.impls

import akka.actor.{Actor, ActorLogging, Props}
import com.github.tamales.Publisher.{TaskFound, TaskSaved}
import com.github.tamales.{ActorConfig, Task, TaskId}
import okhttp3._
import play.api.libs.json._

import java.io.IOException
import java.net.URI
import scala.util.{Failure, Success, Try}

object Todoist {
  def props() = Props(new Todoist())
}
class Todoist extends Actor with ActorLogging with ActorConfig {
  private val http = new OkHttpClient()
  private val cfg = new Cfg

  override def receive: Receive = { case TaskFound(task, emitter) =>
    createOrUpdate(task).foreach { location =>
      emitter ! TaskSaved(task, Some(location))
    }
  }

  private def createOrUpdate(task: Task): Try[URI] = {
    log.info(
      s"Creating or updating task '${task.summary} (${task.id})' into GitLab"
    )
    Try(task.id.self)
  }

  class Cfg {

  }
}
