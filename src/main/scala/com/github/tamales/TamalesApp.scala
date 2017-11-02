package com.github.tamales


import akka.actor.{ActorSystem, Terminated}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object TamalesApp extends App {
  import scala.concurrent.ExecutionContext.Implicits.global
  val system = ActorSystem("tasks-system")

  try {
    val supervisor = system.actorOf(TamalesSupervisor.props(), "tasks-supervisor")
  } finally {
   // system.scheduler.scheduleOnce(5 seconds, ()=>waitFor(system.terminate()))
  }

  private def waitFor(termination:Future[Terminated]): Unit = {
    Await.ready(termination, 1 minute)
    println("Done")
  }

}
