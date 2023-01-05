
// Build the docker image with:
// scala-cli package --docker SpanGenerator.scala --docker-image-repository span-generator


//> using lib "ch.qos.logback:logback-classic:1.3.3"
//> using lib "org.typelevel::cats-effect:3.4.3"

//> using lib "io.kamon::kamon-core:2.5.9"
//> using lib "io.kamon::kamon-opentelemetry:2.5.9"

import cats.effect.{IO, IOApp}
import cats.implicits._
import scala.concurrent.duration._
import kamon.Kamon
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object HelloWorld extends IOApp.Simple:

  val config = ConfigFactory.parseString(
    """
      |kamon {
      |  environment {
      |    service = "SpanGenerator"
      |  }
      |
      |  modules {
      |    otel-trace-reporter {
      |      enabled = true
      |    }
      |  }
      |}
      
    """.stripMargin
  ).withFallback(ConfigFactory.load())
  .resolve()

  def generateSpans(interval: FiniteDuration) =
    val buildSpan = IO {
      Kamon.span("span-generator", "loop") {
        println("Generating span")
      }
    }
      
    lazy val loop: IO[Unit] = buildSpan >> IO.sleep(interval) >> loop

    loop

  val run = for
    _ <- IO(Kamon.init(config))
    _ <- generateSpans(1.seconds)
    _ <- IO.fromFuture(IO(Kamon.stop()))
  yield ()
