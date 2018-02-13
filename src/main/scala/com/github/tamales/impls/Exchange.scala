package com.github.tamales.impls

import java.net.{URI, URLEncoder}
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import akka.util.Timeout
import com.github.tamales.Provider.Refresh
import com.github.tamales.Publisher.TaskFound
import com.github.tamales.impls.Exchange.Found
import com.github.tamales.{ActorConfig, Task, TaskId, TasksEventBus}
import microsoft.exchange.webservices.data.core.enumeration.misc.ExchangeVersion
import microsoft.exchange.webservices.data.core.enumeration.property.{MapiPropertyType, WellKnownFolderName}
import microsoft.exchange.webservices.data.core.exception.service.remote.ServiceResponseException
import microsoft.exchange.webservices.data.core.service.folder.Folder
import microsoft.exchange.webservices.data.core.service.item.EmailMessage
import microsoft.exchange.webservices.data.core.service.schema.TaskSchema
import microsoft.exchange.webservices.data.core.{ExchangeService, PropertySet}
import microsoft.exchange.webservices.data.credential.WebCredentials
import microsoft.exchange.webservices.data.property.definition.ExtendedPropertyDefinition
import microsoft.exchange.webservices.data.search.filter.SearchFilter
import microsoft.exchange.webservices.data.search.filter.SearchFilter.IsEqualTo
import microsoft.exchange.webservices.data.search.{FolderView, ItemView}
import org.apache.http.config.Registry

import scala.collection.JavaConverters._


object Exchange {
  def props(events: TasksEventBus) = Props(new Exchange(events))
  case class Found(tasks:Seq[Task]) {
    override def toString: String = s"${super.toString} with ${tasks.size} task(s)"
  }
}

class Exchange(private val events: TasksEventBus) extends EwsActor {
  /* Tried to use separate actors to Find and Browse but the service has strange behavior when used with different
   * actors. More stable solution is to use imperative and blocking style to walk the folders tree and search in each.
   */
  implicit val timeout = Timeout(20, TimeUnit.SECONDS)
  private var counter = 0

  private object FollowUp {
    val Property = new ExtendedPropertyDefinition(0x1090, MapiPropertyType.Integer)
    val incomplete = 2
  }

  def receive:Receive = {
    case Refresh =>
      log.info("Searching tasks from Exchange's tasks and mails")
      searchIn(WellKnownFolderName.Tasks)
      browse(Folder.bind(service, WellKnownFolderName.Root))

    case Found(tasks) =>
      tasks.foreach { task =>
        log.info(s"Found '${task.summary}' in ${task.id}")
        events.publish(TaskFound(task, self))
        counter -= 1
      }
      terminateIfDone()
  }

  private def terminateIfDone() = if ( counter<=0 ) {
    self ! PoisonPill
  } else {
    log.debug("Cannot self stop actor, still {} tasks to publish", counter)
  }

  private def browse(folder: Folder):Unit = {
    try {
      log.debug("Browsing " + folder.getDisplayName)
      searchIn(folder)
      folder.findFolders(new FolderView(Integer.MAX_VALUE)).getFolders.asScala
        .foreach(folder => {
          browse(folder)
        })
    } catch {
      case e:ServiceResponseException =>
        log.error(s"Failed to browse {} : {}.", folder.getDisplayName, e)
    }
  }

  private def searchIn(folderName: WellKnownFolderName) = {
    val tasks = select(from = folderName, where=new IsEqualTo(TaskSchema.IsComplete, false)).map { task =>
      new Task(
        TaskId(service.getUrl.resolve("/tasks/" + task.getId)),
        task.getSubject,
        Some(false))
    }
    found(tasks)
  }

  private def searchIn(folder: Folder):Unit = {
    val properties = {
      val properties = new PropertySet(PropertySet.FirstClassProperties.getBasePropertySet)
      properties.add(FollowUp.Property)
      properties
    }
    val tasks = select(properties, from=folder, where=new IsEqualTo(FollowUp.Property, FollowUp.incomplete)).map { email =>
      val path = URLEncoder.encode(folder.getDisplayName +"/"+ email.getSubject, "UTF-8")
      val host = "https://"+service.getUrl.toString+"/owa/#"
      new com.github.tamales.Task(
        TaskId(URI.create(host+path)),
        email.getSubject,
        Some(false)
      )
    }
    found(tasks)
  }

  private def found(tasks: Seq[Task]) = if ( tasks.nonEmpty ) {
    counter += tasks.size
    self ! Found(tasks)
  }

  private def select(from:WellKnownFolderName, where:SearchFilter):Seq[microsoft.exchange.webservices.data.core.service.item.Task] = {
    log.debug(s"Selecting items from $from where $where")
    val result = service.findItems(from, where, new ItemView(Integer.MAX_VALUE))
    if ( result.getTotalCount>0 ) {
      service.loadPropertiesForItems(result, PropertySet.FirstClassProperties)
      result.getItems.asScala.map(_.asInstanceOf[microsoft.exchange.webservices.data.core.service.item.Task])
    } else {
      Seq.empty[microsoft.exchange.webservices.data.core.service.item.Task]
    }
  }

  private def select(properties:PropertySet, from:Folder, where:SearchFilter):Seq[EmailMessage] = {
    log.debug(s"Selecting $properties from $from where $where")
    try {
      val result = service.findItems(from.getId, where, new ItemView(Integer.MAX_VALUE))
      if (result.getTotalCount > 0) {
        service.loadPropertiesForItems(result, properties)
        result.getItems.asScala.map(_.asInstanceOf[EmailMessage])
      } else {
        Seq.empty[EmailMessage]
      }
    } catch {
      case e:ServiceResponseException =>
        log.error(s"Failed to select {} from {} : {}.", properties, from.getDisplayName, e)
        Nil
    }
  }
}



abstract class EwsActor extends Actor with ActorConfig with ActorLogging {
  import java.security.GeneralSecurityException

  import microsoft.exchange.webservices.data.EWSConstants
  import microsoft.exchange.webservices.data.core.EwsSSLProtocolSocketFactory
  import org.apache.http.config.RegistryBuilder
  import org.apache.http.conn.socket.{ConnectionSocketFactory, PlainConnectionSocketFactory}
  import org.apache.http.conn.ssl.NoopHostnameVerifier

  private val cfg = new Cfg
  protected lazy val service:ExchangeService = {
    val credentials = new WebCredentials(cfg.username, cfg.password, cfg.domain)
    val service = new ExchangeService(ExchangeVersion.Exchange2010_SP2){
      override def createConnectionSocketFactoryRegistry():Registry[ConnectionSocketFactory] = {
        try {
          val trust = new X509TrustManager {
            override def getAcceptedIssuers:Array[X509Certificate] = Array[X509Certificate]()

            override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String):Unit = ()
            override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String):Unit = ()
          }
          RegistryBuilder.create[ConnectionSocketFactory]
            .register(EWSConstants.HTTP_SCHEME, new PlainConnectionSocketFactory)
            .register(EWSConstants.HTTPS_SCHEME, EwsSSLProtocolSocketFactory.build(trust, NoopHostnameVerifier.INSTANCE))
            .build
        } catch {
          case e: GeneralSecurityException =>
            throw new RuntimeException("Could not initialize ConnectionSocketFactory instances for HttpClientConnectionManager in Exchange provider", e)
        }
      }
    }
    service.setCredentials(credentials)
    service.setUrl(cfg.url)
    service
  }

  private class Cfg {
    private [EwsActor] val (username, domain) = {
      val array = config.getString("providers.exchange.account").split('@')
      (array(0), array(1))
    }
    private [EwsActor] val password = config.getString("providers.exchange.password")
    private [EwsActor] val url = new URI(config.getString("providers.exchange.url"))
  }
}
