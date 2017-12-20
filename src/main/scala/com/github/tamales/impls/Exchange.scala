package com.github.tamales.impls

import java.net.URI

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.github.tamales.Provider.Refresh
import com.github.tamales.Publisher.TaskFound
import com.github.tamales.impls.Browser.Browse
import com.github.tamales.impls.EmailMessage.FollowUp
import com.github.tamales.impls.Finder.{FindFollowed, FindTasks, Found}
import com.github.tamales.{ActorConfig, TaskId, TasksEventBus}
import microsoft.exchange.webservices.data.core.enumeration.misc.ExchangeVersion
import microsoft.exchange.webservices.data.core.enumeration.property.{MapiPropertyType, WellKnownFolderName}
import microsoft.exchange.webservices.data.core.service.folder.Folder
import microsoft.exchange.webservices.data.core.service.item.{EmailMessage, Task}
import microsoft.exchange.webservices.data.core.service.schema.TaskSchema
import microsoft.exchange.webservices.data.core.{ExchangeService, PropertySet}
import microsoft.exchange.webservices.data.credential.WebCredentials
import microsoft.exchange.webservices.data.property.complex.FolderId
import microsoft.exchange.webservices.data.property.definition.ExtendedPropertyDefinition
import microsoft.exchange.webservices.data.search.filter.SearchFilter
import microsoft.exchange.webservices.data.search.filter.SearchFilter.IsEqualTo
import microsoft.exchange.webservices.data.search.{FolderView, ItemView}

import scala.collection.JavaConverters._


object Exchange {
  def props(events: TasksEventBus) = Props(new Exchange(events))
}
class Exchange(private val events: TasksEventBus) extends Actor with ActorLogging {

  def receive:Receive = {
    case Refresh =>
      log.info("Searching tasks from Exchange's tasks and mails")
      context.actorOf(Props[Finder]) ! FindTasks
      context.actorOf(Props[Browser]) ! Browse(Left(WellKnownFolderName.Root))

    case in:FolderId =>
      context.actorOf(Props[Finder]) ! FindFollowed(in)
      context.actorOf(Props[Browser]) ! Browse(Right(in))

    case Found(tasks) =>
      tasks.foreach { task =>
        log.info(s"Found ${task.id} from Exchange")
        events.publish(TaskFound(task, self))
      }
  }

}

object EmailMessage {
  object FollowUp {
    val Property = new ExtendedPropertyDefinition(0x1090, MapiPropertyType.Integer)
    val incomplete = 2
  }
}

object Finder {
  case class FindFollowed(folderId: FolderId)
  case object FindTasks
  case class Found(tasks:Seq[com.github.tamales.Task])
}
private class Finder extends EwsActor {

  private val properties = {
    val properties = new PropertySet(PropertySet.FirstClassProperties.getBasePropertySet)
    properties.add(FollowUp.Property)
    properties
  }

  def receive:Receive = {
    case FindTasks =>
      val tasks = select(from = WellKnownFolderName.Tasks, where=new IsEqualTo(TaskSchema.IsComplete, false)).map { task =>
        new com.github.tamales.Task(
          TaskId(service.getUrl.resolve("/tasks/" + task.getId.getUniqueId)),
          task.getSubject,
          Some(false))
      }
      sender ! Found(tasks)

    case FindFollowed(folder) =>
      val tasks = select(properties, from=folder, where=new IsEqualTo(FollowUp.Property, FollowUp.incomplete)).map { email =>
        new com.github.tamales.Task(
          TaskId(service.getUrl.resolve("/emails/" + email.getId.getUniqueId)),
          email.getSubject,
          Some(false)
        )
      }
      sender ! Found(tasks)
  }

  private def select(from:WellKnownFolderName, where:SearchFilter):Seq[Task] = {
    val result = service.findItems(from, where, new ItemView(Integer.MAX_VALUE))
    if ( result.getTotalCount>0 ) {
      service.loadPropertiesForItems(result, PropertySet.FirstClassProperties)
      result.getItems.asScala.map(_.asInstanceOf[Task])
    } else {
      Seq.empty[Task]
    }
  }

  private def select(properties:PropertySet, from:FolderId, where:SearchFilter):Seq[EmailMessage] = {
    val result = service.findItems(from, where, new ItemView(Integer.MAX_VALUE))
    if ( result.getTotalCount>0 ) {
      service.loadPropertiesForItems(result, properties)
      result.getItems.asScala.map(_.asInstanceOf[EmailMessage])
    } else {
      Seq.empty[EmailMessage]
    }
  }

}

object Browser {
  case class Browse(folder:Either[WellKnownFolderName, FolderId])
}
private class Browser extends EwsActor {
  def receive:Receive = {
    case Browse(Left(name)) =>
      browse(Folder.bind(service, name), sender)

    case Browse(Right(id)) =>
      browse(Folder.bind(service, id), sender)
  }

  private def browse(folder: Folder, sender: ActorRef) = {
    folder.findFolders(new FolderView(Integer.MAX_VALUE)).getFolders.asScala
      .foreach( f => sender ! f.getId)
  }
}

abstract class EwsActor extends Actor with ActorConfig with ActorLogging {
  private val cfg = new Cfg
  protected lazy val service:ExchangeService = {
    val credentials = new WebCredentials(cfg.username, cfg.password, cfg.domain)
    val service = new ExchangeService(ExchangeVersion.Exchange2010_SP2)
    service.setCredentials(credentials)
    service.setUrl(cfg.url)
    service
  }

  private class Cfg {
    private [EwsActor] val (username, domain) = config.getString("providers.exchange.account").split("\\/", 1)
    private [EwsActor] val password = config.getString("providers.exchange.password")
    private [EwsActor] val url = new URI(config.getString("providers.exchange.url"))
  }
}
