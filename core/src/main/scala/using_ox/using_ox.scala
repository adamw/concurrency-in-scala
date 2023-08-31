package using_ox

import ox.*
import ox.channels.*
import scala.concurrent.duration.*

trait ServerSocket:
  def accept: ClientSocket

trait ClientSocket

trait HttpRequest:
  def param(name: String): String
case class HttpResponse(code: Int, body: String)

trait WebSocket:
  def sendText(text: String): Unit // must be called sequentially

def read(socket: ClientSocket): HttpRequest = ???
def write(resp: HttpResponse, socket: ClientSocket): HttpRequest = ???

def mealServer(
    s: ServerSocket,
    ingredientWebSockets: Map[String, Channel[Demand]]
) =
  scoped {
    forever {
      val socket = s.accept
      fork {
        val req = read(socket)
        val resp = prepareMeal(req, ingredientWebSockets)
        write(resp, socket)
      }
    }
  }

//

case class Meal(name: String, ingredients: List[Ingredient])
case class Ingredient(name: String)
case class Demand(amount: Int)

def startWebSocketChannel(ws: WebSocket)(using Ox): Sink[Demand] =
  val c = Channel[Demand](16)
  forkDaemon {
    repeatWhile {
      c.receive() match
        case e: ChannelClosed.Error => throw e.toThrowable
        case ChannelClosed.Done     => false
        case Demand(amount) =>
          ws.sendText(amount.toString)
          true
    }
  }
  c

def findInDB(mealName: String): Meal = ???
def findInCache(mealName: String): Meal = ???

def prepareMeal(
    req: HttpRequest,
    ingredientWebSockets: Map[String, Sink[Demand]]
): HttpResponse =
  val mealName = req.param("meal")
  val meal: Meal =
    raceSuccess(retry(3, 100.millis)(findInDB(mealName)))(findInCache(mealName))
  par(meal.ingredients.map { i => () =>
    ingredientWebSockets(i.name).send(Demand(1))
  })
  HttpResponse(200, "OK")
