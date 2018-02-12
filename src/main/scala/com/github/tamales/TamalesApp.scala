package com.github.tamales


import akka.actor.{ActorSystem, Terminated}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

object TamalesApp extends App {
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
