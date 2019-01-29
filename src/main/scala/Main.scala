import java.awt.image.{BufferedImage, RenderedImage}
import java.io.{ByteArrayOutputStream, File}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source, StreamConverters}
import akka.util.ByteString
import com.recognition.software.jdeskew.{ImageDeskew, ImageUtil}
import javax.imageio.ImageIO
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.util.ImageHelper
import org.bytedeco.javacpp.opencv_core._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import org.bytedeco.javacv.Java2DFrameUtils
import org.bytedeco.javacpp.indexer.UByteRawIndexer

import scala.concurrent.ExecutionContextExecutor

object Main extends App with OCR with Spell {
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
    Photo.fastNlMeansDenoising(mat, src, 40, 7, 21)
    val dst = src.clone()
    Photo.detailEnhance(src,dst)
    dst
  })


  import org.bytedeco.javacpp.{opencv_imgproc => Imgproc}
  // Not working yet
  def edgeDetectMat = Flow[Mat].map(src => {
    val grey = src.clone()
    val blur = src.clone()
    val edges = src.clone()
    Imgproc.cvtColor(src,grey,Imgproc.COLOR_BGR2GRAY)
    Imgproc.GaussianBlur(grey,blur,new Size(3,3), 0)
    Imgproc.Canny(blur, edges, 75.0f, 200.0f)

    val contours = new MatVector()
    Imgproc.findContours(edges, contours, Imgproc.CV_RETR_TREE, Imgproc.CV_CHAIN_APPROX_SIMPLE)
    val markers = Mat.zeros(edges.size(), CV_32SC1).asMat()

    val x = contours.get().toList.sortBy(con => -Imgproc.contourArea(con)).take(5)
    for(i <- 0 until 5) {
      val mat = x(i)
      val approxDistance = Imgproc.arcLength(mat, true) * 0.02
      val approxCurve = new Mat()
      if(approxDistance > 1) {
        Imgproc.approxPolyDP(mat, approxCurve, approxDistance, true)
        if(approxCurve.total() == 4) {
          Imgproc.drawContours(markers,new MatVector(approxCurve),i.toInt,Scalar.all(i + 1.0))
        }
      }
    }

    edges
  })

  def spellCheck = Flow[String].map(ocr => {
    import scala.collection.JavaConverters._
    val words: Set[String] = ocr.replaceAll("\n", " ").split("\\s+")
      .map(_.replaceAll(
      "[^a-zA-Z'’\\d\\s:]", "") // Remove most punctuation
      .trim)
      .filter(!_.isEmpty).toSet
    val misspelled = words.filter(word => !speller.isCorrect(word))
    val suggestions: Set[Map[String, List[String]]] = misspelled.map(mis => {
      Map(mis -> speller.suggest(mis).asScala.toList)
    })
    OcrString(ocr, suggestions)
  })

  def imageOcr = Flow[BufferedImage].map(bi => {
    val ocr = tesseract.doOCR(bi)
    ocr
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

  def imageSink(path:String, format:String = "png") = Sink.foreach[BufferedImage](bi => {
    ImageIO.write(bi, format, new File(path))
  })

  val enhanceFlow = imageToBinaryImage.alsoTo(imageSink("binary.png"))
    .via(bufferedImageToMat)
    .via(enhanceMat)
    .via(matToBufferedImage).alsoTo(imageSink("enhanced.png"))
    .via(imageDeSkew())

  val ocrFlow = imageOcr
    .via(spellCheck)

  val edgeSink = bufferedImageToMat
    .via(edgeDetectMat)
    .via(matToBufferedImage)
    .to(imageSink("edge.png"))

//  import GraphDSL.Implicits._
//  val imageGraph = GraphDSL.create() { implicit builder =>
//    val img2Binary = builder.add(imageToBinaryImage)
//    val buff2Mat = builder.add(bufferedImageToMat)
//    val broadcast = builder.add(Broadcast[Mat](2))
//    val enhance = builder.add(enhanceMat)
//    val mat2Buff = builder.add(matToBufferedImage)
//    val mat2Buff2 = builder.add(matToBufferedImage)
//    val deskew = builder.add(imageDeSkew())
//    val edgeDetect = builder.add(edgeDetectMat)
//    val edgeSink = builder.add(imageSink("edge.png"))
//
//    img2Binary ~> buff2Mat ~> broadcast ~> enhance ~> mat2Buff ~> deskew
//                              broadcast ~> edgeDetect ~> mat2Buff2 ~> edgeSink
//
//    FlowShape(img2Binary.in, deskew.out)
//  }

  val route = path("image" / "ocr") {
    post {
      fileUpload("fileUpload") {
        case (_, fileStream) =>
          val inputStream = fileStream.runWith(StreamConverters.asInputStream())
          val image = ImageIO.read(inputStream)

          val ocr = Source
            .single(image).alsoTo(edgeSink)
            .via(enhanceFlow)
            .via(ocrFlow)

          complete(ocr)
      }
    }
  }

  Http().bindAndHandle(route, "localhost", 8080)
}

case class OcrString(ocr:String, suggestions: Set[Map[String, List[String]]])

trait OCR {
  val tesseract: Tesseract = new Tesseract
  tesseract.setDatapath("/usr/local/Cellar/tesseract/4.0.0/share/tessdata/")
}

trait Spell {
  import com.atlascopco.hunspell.Hunspell
  val speller = new Hunspell("en_US.dic", "en_US.aff")
}

object MyJsonProtocol
  extends SprayJsonSupport
    with DefaultJsonProtocol {
  implicit val ocrFormat: RootJsonFormat[OcrString] = jsonFormat2(OcrString.apply)
}