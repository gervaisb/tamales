package com.github.tamales.impls

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import com.github.tamales.Provider.Refresh
import com.github.tamales.Publisher.TaskFound
import com.github.tamales.{ActorConfig, Task, TaskId, TasksEventBus}
import okhttp3.{Request, Response}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import java.io.IOException
import java.net.{URI, URL, URLEncoder}
import java.util.Base64
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

object GitLab {
  def props(events: TasksEventBus) = Props(new GitLab(events))
}
class GitLab(val events: TasksEventBus)
    extends Actor
    with ActorLogging
    with ActorConfig {
  private val cfg = new Cfg
  private val http = UnsafeOkHttpClientBuilder.build()

  override def receive: Receive = { case Refresh =>
    search().foreach { task =>
      log.info(s"Found ${task.id} from GitLab")
      events.publish(TaskFound(task, self))
    }
    self ! PoisonPill // No need to get event back
  }

  private def search(): Seq[Task] = {
    val request = new Request.Builder()
      .header("PRIVATE-TOKEN", cfg.token)
      .url(s"${cfg.location}/api/v4/todos")
      .build()
    execute(request).map { response =>
      val array = Json.parse(response.body().bytes()).as[JsArray]
      array.value.map { json =>
        println(makeTitle(json))
        new Task(
          TaskId(URI.create((json \ "target_url").as[String])),
          makeTitle(json),
          Some(false)
        )
      }
    }.get
  }

  private def makeTitle(json: JsValue): String = {
    val title = (json \ "target" \ "title").as[String]
    val kind = (json \ "target_type").as[String]
    val iid = (json \ "target" \ "iid").as[Int]
    kind match {
      case "MergeRequest" => s"!$iid; $title"
      case "Issue"        => s"#$iid; $title"
      case _              => title
    }
  }

  private def execute(request: Request): Try[Response] = {
    val response = http.newCall(request).execute()
    if (response.isSuccessful) {
      Success(response)
    } else {
      Failure(
        new IOException(
          s"${request.method()} ${request.url()} -> Http_${response.code}"
        )
      )
    }
  }

  class Cfg {
    val location: String = config.getString("providers.gitlab.location")
    val token: String = config.getString("providers.gitlab.access_token")
  }

}
