package using_zio

import zio.*

trait ServerSocket:
  def accept: Task[ClientSocket]

trait ClientSocket

trait HttpRequest:
  def param(name: String): String
case class HttpResponse(code: Int, body: String)

trait WebSocket:
  def sendText(text: String): Task[Unit] // must be called sequentially

def read(socket: ClientSocket): Task[HttpRequest] = ???
def write(resp: HttpResponse, socket: ClientSocket): Task[HttpRequest] = ???

def mealServer(
    s: ServerSocket,
    ingredientWebSockets: Ref[Map[String, Queue[Demand]]]
) =
  s.accept.flatMap { socket =>
    val handleSocket = for {
      req <- read(socket)
      resp <- prepareMeal(req, ingredientWebSockets)
      _ <- write(resp, socket)
    } yield ()

    handleSocket.fork // supervision
  }.forever

//

case class Meal(name: String, ingredients: List[Ingredient])
case class Ingredient(name: String)
case class Demand(amount: Int)

def startWebSocketQueue(ws: WebSocket): Task[Queue[Demand]] =
  Queue.bounded[Demand](16).flatMap { queue => // global process
    queue.take
      .flatMap { case Demand(amount) => ws.sendText(amount.toString) }
      .forever
      .fork
      .map(_ => queue)
  }

def findInDB(mealName: String): Task[Meal] = ???
def findInCache(mealName: String): Task[Meal] = ???

def prepareMeal(
    req: HttpRequest,
    ingredientWebSockets: Ref[Map[String, Queue[Demand]]]
): Task[HttpResponse] =
  val mealName = req.param("meal")
  val meal: Task[Meal] = ZIO.raceAll( // race
    findInDB(mealName).retry(
      Schedule.spaced(100.millis) && Schedule.recurs(3)
    ), // retry, testing
    List(findInCache(mealName))
  )
  val sendDemand = meal.flatMap { m =>
    ZIO.foreachPar(m.ingredients)(i => // par
      ingredientWebSockets.get.flatMap(_.apply(i.name).offer(Demand(1)))
    ) // shared state, message passing
  }
  sendDemand.map(_ => HttpResponse(200, "OK"))
