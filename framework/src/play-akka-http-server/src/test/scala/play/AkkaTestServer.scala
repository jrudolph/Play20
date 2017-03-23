package play

import play.core.server._
import play.api.routing.sird._
import play.api.mvc._

import akka._
import akka.http._

object AkkaTestServer extends App {

  val port: Int = 9000
  val server = AkkaHttpServer.fromRouter(ServerConfig(
    port = Some(port),
    address = "127.0.0.1"
  )) {
    case GET(p"/") => Action { implicit req =>
      Results.Ok(s"Hello world")
    }
  }
  println("Server (Akka HTTP) started: http://127.0.0.1:9000/ ")

  /*
      ideas to try:
        [ ] try to not convert headers eagerly, extend Headers and only convert on demand?
        [ ] make the akka http parser not parse anything except the bare minimum, emit all the rest as raw
        [ ] avoid eagerly converting RequestHeader, how much can we do lazily?
        [√] use sub fusing materializer for the Aggregator so we dont create actors in there
        [√] fix the Source.empty in Akka so it's a GraphStage (improves perf of running/materializing it)
        [ ] use new materializer!
          [ ] special case the Source.empty somehow? We just need a future from it in the Play usage, special phase to use there?
        [ ] if materialization becomes really fast, actor creation will be the bottleneck perhaps, so we might want to see:
              what happens if you take the connection Source, 
              attach it to a Balance, 
              and then balance out to a pool of Sinks behind an async boundary
   */

  // server.stop()
}
