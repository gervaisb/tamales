package com.github.tamales.impls

import java.io.IOException
import java.net.URI

import akka.actor.{Actor, ActorLogging, Props}
import com.github.tamales.Publisher.{TaskFound, TaskSaved}
import com.github.tamales.{ActorConfig, Task, TaskId}
import okhttp3._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

/**
  * Trello is a publisher that create and update tasks in a given board.
  * The tasks that are done are moved to a configured 'complete' list while the
  * others are moved into the 'incomplete' configured list.
  * Each task are represented via a card with a link to the provider task URI
  */
// TODO, Add a checklist item for tasks that are tracked in a "project" card but still link them
object Trello {
  def props() = Props(new Trello())
}
class Trello extends Actor with ActorLogging with ActorConfig {
  import Trello._
  private val http = new OkHttpClient()
  private val cfg = new Cfg
  private val Backlog = cfg.incompleteListId
  private val Done = cfg.completeListId

  override def receive:Receive = {
    case TaskFound(task, emitter) =>
      createOrUpdate(task).foreach { location =>
        emitter ! TaskSaved(task, Some(location))
      }
  }

  private def createOrUpdate(task:Task):Try[URI] =
    find(task.id).fold {
      create(task)
    }{ current =>
      update(current.copy(task = current.task.update(task)))
    }

  private def create(task:Task):Try[URI] = {
    log.info(s"Creating task '${task.summary} (${task.id})' into Trello")
    val payload = new FormBody.Builder()
      .add("name", task.summary)
      .add("idList", listOf(task))
      .add("urlSource", task.id.self.toString)
      .build()
    val request = new Request.Builder()
      .url(pathTo("cards"))
      .post(payload)
      .build()
    execute(request).map { r =>
      val json = Json.parse(r.body().bytes())
      val url  = json \ "shortUrl"
      URI.create(url.as[String])
    }
  }

  private def update(card:Card):Try[URI] = {
    log.info(s"Updating card '${card.name} (${card.id})' for task '${card.task.summary} (${card.task.id.self})' into Trello")

    val newList: String = listOf(card.task)
    val value = new FormBody.Builder()
      .add("value", newList)
      .build()
    val request = new Request.Builder()
      .url(pathTo(s"cards/${card.id}/idList"))
      .put(value)
      .build()
    execute(request).map { r =>
      val json = Json.parse(r.body().bytes())
      val url  = json \ "shortUrl"
      URI.create(url.as[String])
    }
  }

  private def find(id:TaskId):Option[Card] = {
    val query = s"""&card_fields=name,idList,idChecklists&idBoards=${cfg.boardId}&query=is:open has:attachments "${id.self}"""
    val request = new Request.Builder()
      .url(pathTo("search") + query)
      .build()
    execute(request) match {
      case Success(response) =>
        val cards = (Json.parse(response.body().bytes()) \ "cards").as[JsArray].value
        if ( cards.isEmpty ) {
          None
        } else {
          val json = cards.head
          val task = new Task(id, (json \ "name").as[String], Some((json \ "idList").as[String] == Done))
          val card = Card((json \ "id").as[String], (json \ "name").as[String], (json \ "idList").as[String], (json \ "idChecklists").asOpt[String], task)
          Some(card)
        }
      case Failure(e) => throw e
    }
  }

  private def execute(request:Request):Try[Response] = {
    val response = http.newCall(request).execute()
    if (response.isSuccessful) {
      Success(response)
    } else {
      Failure(new IOException(s"${request.method()} ${request.url()} -> Http_${response.code}"))
    }
  }

  private def pathTo(fragment: String):String = {
    s"https://trello.com/1/$fragment?key=${cfg.api.key}&token=${cfg.api.token}"
  }

  private def listOf(task: Task) = task.done.orElse(Some(false)).map {
      case true => Done
      case _ => Backlog
    }.get

  case class Card(id:String, name:String, list:String, checklist:Option[String], task:Task)

  class Cfg {
    case class Api(
                    key: String = config.getString("publishers.trello.api.key"),
                    token: String = config.getString("publishers.trello.api.token")
                  )
    val api = Api()
    val boardId:String = config.getString("publishers.trello.boardId")
    val incompleteListId: String = config.getString("publishers.trello.list.incompleteId")
    val completeListId: String = config.getString("publishers.trello.list.completeId")
  }
}
