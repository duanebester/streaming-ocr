import java.awt.image.{BufferedImage, RenderedImage}
import java.io.ByteArrayOutputStream

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source, StreamConverters}
import akka.util.ByteString
import com.recognition.software.jdeskew.{ImageDeskew, ImageUtil}
import javax.imageio.ImageIO
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.util.ImageHelper
import org.bytedeco.javacpp.indexer.UByteRawIndexer
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import org.bytedeco.javacpp.opencv_core._
import org.bytedeco.javacv.Java2DFrameUtils

import scala.concurrent.ExecutionContextExecutor

object Main extends App with OCR {
  implicit val system: ActorSystem = ActorSystem("ocr")
  implicit val executor: ExecutionContextExecutor = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()
  import MyJsonProtocol._

  def imageDeSkew(skewThreshold:Double = 0.05) = Flow[BufferedImage].map(bi => {
    val deSkew = new ImageDeskew(bi)
    val imageSkewAngle = deSkew.getSkewAngle

    if (imageSkewAngle > skewThreshold || imageSkewAngle < -skewThreshold) {
      ImageUtil.rotate(bi, -imageSkewAngle, bi.getWidth() / 2, bi.getHeight() / 2)
    } else {
      bi
    }
  })

  def imageToBinaryImage = Flow[BufferedImage].map(img => {
    val bin = ImageHelper.convertImageToBinary(img)
    bin
  })

  def bufferedImageToMat = Flow[BufferedImage].map(bi => {
    val mat = new Mat(bi.getHeight, bi.getWidth, CV_8UC(3))
    val indexer:UByteRawIndexer = mat.createIndexer()
    for (y <- 0 until bi.getHeight()) {
      for (x <- 0 until bi.getWidth()) {
        val rgb = bi.getRGB(x, y)
        indexer.put(y, x, 0, (rgb >> 0) & 0xFF)
        indexer.put(y, x, 1, (rgb >> 8) & 0xFF)
        indexer.put(y, x, 2, (rgb >> 16) & 0xFF)
      }
    }
    indexer.release()
    mat
  })

  def matToBufferedImage = Flow[Mat].map(mat => {
    Java2DFrameUtils.toBufferedImage(mat)
  })

  import org.bytedeco.javacpp.{opencv_photo => Photo}
  def enhanceMat = Flow[Mat].map(mat => {
    val src = mat.clone()
    Photo.fastNlMeansDenoising(mat, src, 40, 10, 40)
    val dst = src.clone()
    Photo.detailEnhance(src,dst)
    dst
  })

  def imageOcr = Flow[BufferedImage].map(bi => {
    val ocr = tesseract.doOCR(bi)
    OcrString(ocr)
  })

  def imageWriter(format:String = "png") = Flow[BufferedImage].map(bi => {
    val ri = bi.asInstanceOf[RenderedImage]
    val outPutStream = new ByteArrayOutputStream
    val writer = ImageIO.getImageWritersByFormatName(format).next()
    val imageOutputStream = ImageIO.createImageOutputStream(outPutStream)

    writer.setOutput(imageOutputStream)
    writer.write(ri)

    val bytes = outPutStream.toByteArray
    outPutStream.close()
    ByteString(bytes)
  })

  val route = path("image" / "ocr") {
    post {
      fileUpload("fileUpload") {
        case (_, fileStream) =>
          val inputStream = fileStream.runWith(StreamConverters.asInputStream())
          val image = ImageIO.read(inputStream)

          val ocr = Source
            .single(image)
            .via(imageToBinaryImage)
            .via(bufferedImageToMat)
            .via(enhanceMat)
            .via(matToBufferedImage)
            .via(imageDeSkew())
            .via(imageOcr)

          complete(ocr)
      }
    }
  }

  Http().bindAndHandle(route, "localhost", 8080)
}

case class OcrString(ocr:String)

trait OCR {
  val tesseract: Tesseract = new Tesseract
  tesseract.setDatapath("/usr/local/Cellar/tesseract/4.0.0/share/tessdata/")
}

object MyJsonProtocol
  extends SprayJsonSupport
    with DefaultJsonProtocol {
  implicit val ocrFormat: RootJsonFormat[OcrString] = jsonFormat1(OcrString.apply)
}