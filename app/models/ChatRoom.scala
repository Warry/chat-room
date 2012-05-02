package models

import akka.actor._
import akka.util.duration._

import play.api._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._

import akka.util.Timeout
import akka.pattern.ask

import play.api.Play.current

object Robot {

  def apply(chatRoom: ActorRef) {

    // Create an Iteratee that log all messages to the console.

    implicit val timeout = Timeout(1 second)
    // Make the robot join the room
    chatRoom ? (Join("Robot"))

    // Make the robot talk every 10 seconds
    Akka.system.scheduler.schedule(
      10 seconds,
      10 seconds,
      chatRoom,
      Talk("Robot", "I'm still alive", "home")
    )
  }

}

object ChatRoom {

  implicit val timeout = Timeout(1 second)

  lazy val default = {
    val roomActor = Akka.system.actorOf(Props[ChatRoom])
    // Create a bot user (just for fun)
    Robot(roomActor)

    roomActor
  }

  def join(username:String):Promise[(Iteratee[JsValue,_],Enumerator[JsValue])] = {
    (default ? Join(username)).asPromise.map {

      case Connected(enumerator) => 
        // Create an Iteratee to consume the feed
        val iteratee = Iteratee.foreach[JsValue] { json =>

          (json \ "kind").as[String] match {
              case "talk" => default ! Talk(username, (json \ "text").as[String],  (json \ "room").as[String] )
              case "join" => default ! Enter(username, (json \ "room").as[String] )
              case "quit" => default ! Leave(username, (json \ "room").as[String] )
              case "list" => default ! List(username)
              case _ => Logger.info("SocketE:" + json)
          }

        }.mapDone { _ =>
          default ! Quit(username)
        }

        (iteratee,enumerator)

      case CannotConnect(error) => 

        // Connection error
        // A finished Iteratee sending EOF
        val iteratee = Done[JsValue,Unit]((),Input.EOF)

        // Send an error and close the socket
        val enumerator =  Enumerator[JsValue](JsObject(Seq("error" -> JsString(error)))).andThen(Enumerator.enumInput(Input.EOF))

        (iteratee,enumerator)
    }
  }
}

class ChatRoom extends Actor {

  var members = Map.empty[String, Member]

  def receive = {

    case Join(username) => {
      // Create an Enumerator to write to this socket
      val channel =  Enumerator.imperative[JsValue]( onStart = () => self ! NotifyJoin(username) )
      if(members.contains(username)) {
        sender ! CannotConnect("This username is already used")
      } else {
        members = members + (username -> Member(username, channel, Seq("home")))
        sender ! Connected(channel)
      }
    }

    case NotifyJoin(username) => {
      Logger.info("join: " + username)
      notifyAll("connect", username)
      notifyRoom("join", username, "home")
    }

    case Talk(username, text, room) => {
      Logger.info("talk: " + username)
      talk("talk", username, room, text)
    }

    case Enter(username, room) => {
      Member.addUser(username,room)
      notifyRoom("join", username, room)
    }

    case Leave(username, room) => {
      Member.removeUser(username, room)
      notifyRoom("quit", username, room)
    }

    case Quit(username) => {

      members.map { case (k,member) =>
        if(k == username) member.rooms.foreach { r =>
          Member.removeUser(username, r)
          notifyRoom("quit", username, r)
        }
      }

      members = members - username

    }

    case List(username) => {

      val msg = JsObject(
        Seq(
          "kind" -> JsString("list"),
          "members" -> JsObject(
            members.map { case (n,member) => 
              n -> JsArray(member.rooms.map(JsString))
            }.toSeq
        )
      ))
      members.foreach { case (name,member) => {
          if(name ==  username) member.connect.push(msg)
        }
      }

    }


  }

  def talk(kind: String, user: String, room: String, text: String) {
    val msg = JsObject(
      Seq(
        "kind" -> JsString(kind),
        "user" -> JsString(user),
        "room" -> JsString(room),
        "message" -> JsString(text)
      )
    )

    members.foreach { case (_,member) => {
        if(member.rooms.contains(room)) member.connect.push(msg)
      }
    }

  }


  def notifyRoom(kind: String, user: String, room: String) {
    val msg = JsObject(
      Seq(
        "kind" -> JsString(kind),
        "user" -> JsString(user),
        "room" -> JsString(room),
        "members" -> JsArray(
          members.filter { case (_,member) => member.rooms.contains(room) }.keySet.toList.map(JsString)
        )
      )
    )
    members.foreach { case (_,member) => {
        if(member.rooms.contains(room)) member.connect.push(msg)
      }
    }

  }

  def notifyAll(kind: String, user: String) {
    val msg = JsObject(
      Seq(
        "kind" -> JsString(kind),
        "user" -> JsString(user)
      )
    )
    members.mapValues { member =>
      member.connect.push(msg)
    }

  }



  case class Member(username: String, connect: PushEnumerator[JsValue], rooms: Seq[String])

  object Member {

    def addUser(username: String, room: String) = {
      members = members.map { case (k,member) =>
        if (k == username && !member.rooms.contains(room)) {
          ( k-> Member(member.username, member.connect, member.rooms :+ room) )
        } else {
          (k -> member )
        }
      }
    }

    def removeUser(username: String, room: String) = {
      members = members.map { case (k,member) =>
        if (k == username && member.rooms.contains(room)) {

        member.connect.push(JsObject(Seq(
          "kind" -> JsString("quit"),
          "user" -> JsString(username),
          "room" -> JsString(room)
        )))


          ( k -> Member(member.username, member.connect, member.rooms diff Seq(room) ) )
        } else {
          ( k -> member )
        }
      }
    }

  }
}

case class List(username: String)
case class Join(username: String)
case class Quit(username: String)
case class Talk(username: String, text: String, room: String)
case class Enter(username: String, room: String)
case class Leave(username: String, text: String)
case class NotifyJoin(username: String)

case class Connected(enumerator:Enumerator[JsValue])
case class CannotConnect(msg: String)

