package using_pekko

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.pattern.*

import java.util.UUID
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import scala.concurrent.duration.*

trait ServerSocket:
  def accept: Future[ClientSocket]

trait ClientSocket

trait HttpRequest:
  def param(name: String): String
case class HttpResponse(code: Int, body: String)

trait WebSocket:
  def sendText(text: String): Future[Unit] // must be called sequentially

def read(socket: ClientSocket): Future[HttpRequest] = ???
def write(resp: HttpResponse, socket: ClientSocket): Future[HttpRequest] = ???

def mealServer(
    s: ServerSocket,
    actorSystem: ActorSystem
): Future[Unit] =
  import actorSystem.dispatcher
  s.accept
    .flatMap { socket =>
      def handleSocket: Future[Unit] = for {
        req <- read(socket)
        resp <- prepareMeal(req, actorSystem)
        _ <- write(resp, socket)
      } yield ()

      Future {
        handleSocket
      }
    }
    .flatMap(_ => mealServer(s, actorSystem))

//

case class Meal(name: String, ingredients: List[Ingredient])
case class Ingredient(name: String)
case class Demand(amount: Int)

def webSocketBehavior(ws: WebSocket): Behavior[Demand] =
  def run(ready: Boolean, buffer: Vector[Demand]): Behavior[Demand | Boolean] =
    Behaviors.receive[Demand | Boolean] {
      case (ctx, msg: Demand) =>
        import ctx.executionContext
        if ready then
          ws.sendText(msg.amount.toString).onComplete(_ => ctx.self.tell(true))
          run(false, buffer)
        else run(false, buffer :+ msg)
      case (ctx, _: Boolean) =>
        import ctx.executionContext
        buffer match
          case head +: tail =>
            ws.sendText(head.amount.toString)
              .onComplete(_ => ctx.self.tell(true))
            run(false, tail)
          case _ => run(true, buffer)
    }

  run(true, Vector.empty).narrow[Demand]

def raceSuccess[T](
    f1: Future[T],
    f2: Future[T],
    actorSystem: ActorSystem
): Future[T] =
  import actorSystem.dispatcher
  val p = Promise[T]()

  def raceBehavior2(e: Throwable) =
    Behaviors.receiveMessage[Either[Throwable, T]] {
      case Left(_) =>
        p.failure(e)
        Behaviors.stopped
      case Right(v: T) =>
        p.success(v)
        Behaviors.stopped
    }

  val raceBehavior1 =
    Behaviors.receiveMessage[Either[Throwable, T]] {
      case Left(e: Throwable) =>
        raceBehavior2(e)
      case Right(v: T) =>
        p.success(v)
        Behaviors.stopped
    }

  val raceActor = actorSystem.spawn(raceBehavior1, s"race-${UUID.randomUUID()}")
  List(f1, f2).foreach(_.onComplete {
    case Success(v) => raceActor.tell(Right(v))
    case Failure(e) => raceActor.tell(Left(e))
  })
  p.future

def findInDB(mealName: String): Future[Meal] = ???
def findInCache(mealName: String): Future[Meal] = ???

def prepareMeal(
    req: HttpRequest,
    actorSystem: ActorSystem
): Future[HttpResponse] =
  import actorSystem.dispatcher
  implicit val scheduler = actorSystem.scheduler

  val mealName = req.param("meal")

  val findInDBFuture = retry(() => findInDB(mealName), attempts = 3, 100.millis)
  val findInCacheFuture = findInCache(mealName)
  val meal: Future[Meal] =
    raceSuccess(findInDBFuture, findInCacheFuture, actorSystem)

  val sendDemand = meal.map { m =>
    m.ingredients.foreach { i =>
      val wsActor = actorSystem.actorSelection(
        actorSystem.child("user").child(s"${i.name}-websocket")
      )
      wsActor ! Demand(1)
    }
  }

  sendDemand.map(_ => HttpResponse(200, "OK"))
