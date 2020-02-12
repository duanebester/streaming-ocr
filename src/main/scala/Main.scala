import java.awt.image.BufferedImage
import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source, StreamConverters}
import com.recognition.software.jdeskew.{ImageDeskew, ImageUtil}
import javax.imageio.ImageIO
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.util.ImageHelper
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import org.bytedeco.javacv.Java2DFrameUtils
import org.bytedeco.javacpp.indexer.UByteRawIndexer
import org.bytedeco.javacpp.opencv_core._

import scala.concurrent.ExecutionContextExecutor
import opennlp.tools.namefind.TokenNameFinderModel
import opennlp.tools.namefind.NameFinderME
import opennlp.tools.util.Span
import opennlp.tools.tokenize.TokenizerModel
import opennlp.tools.tokenize.TokenizerME
import opennlp.tools.sentdetect.SentenceDetectorME
import opennlp.tools.sentdetect.SentenceModel
import com.joestelmach.natty.Parser

object Main extends App with OCR with Spell with NLP with Natty {
  implicit val system: ActorSystem = ActorSystem("ocr")
  implicit val executor: ExecutionContextExecutor = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()
  import MyJsonProtocol._

  def imageDeSkew(skewThreshold:Double = 0.050) = Flow[BufferedImage].map(bi => {
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
    Photo.detailEnhance(src, dst)
    dst
  })

  def extractPersons = Flow[OcrSuggestions].map(ocr => {
    val tokens = tokenizer.tokenize(ocr.ocr)
    val spans:Array[Span] = personFinderME.find(tokens)
    val persons = spans.toList.map(span => tokens(span.getStart()))
    OcrSuggestionsPersons(ocr.ocr, ocr.suggestions, persons)
  })

  def extractDates = Flow[OcrSuggestionsPersons].map(ocr => {
    val sentences = sentenceDetector.sentDetect(ocr.ocr.replaceAll("\n", " ")).toList
    import scala.collection.JavaConverters._
    val dates = sentences.map(sentence => parser.parse(sentence))
      .flatMap(dateGroups => dateGroups.asScala.toList)
      .map(dateGroup => (dateGroup.getDates().asScala.toList.map(_.toString()), dateGroup.getText()))

    OcrSuggestionsPersonsDates(ocr.ocr, ocr.suggestions, ocr.persons, dates)
  })

  def spellCheck = Flow[String].map(ocr => {
    println(ocr)
    import scala.collection.JavaConverters._
    val words: Set[String] = ocr.replaceAll("-\n", "").replaceAll("\n", " ").replaceAll("-"," ").split("\\s+")
      .map(_.replaceAll(
      "[^a-zA-Z'’\\d\\s]", "") // Remove most punctuation
      .trim)
      .filter(!_.isEmpty).toSet
      println(words)
    val misspelled = words.filter(word => !speller.isCorrect(word))
    val suggestions: Set[Map[String, List[String]]] = misspelled.map(mis => {
      Map(mis -> speller.suggest(mis).asScala.toList)
    })
    OcrSuggestions(ocr, suggestions)
  })

  def imageOcr = Flow[BufferedImage].map(tesseract.doOCR)

  def imageSink(path:String, format:String = "png") = Sink.foreachParallel[BufferedImage](4)(bi => {
    ImageIO.write(bi, format, new File(path))
  })

  val imageEnhance = bufferedImageToMat.via(enhanceMat).via(matToBufferedImage)

  val imagePreProcessFlow =
    imageToBinaryImage.alsoTo(imageSink("binary.png"))
    .via(imageEnhance).alsoTo(imageSink("enhanced.png"))
    .via(imageDeSkew()).alsoTo(imageSink("deskew.png"))

  val ocrFlow = imageOcr.via(spellCheck).via(extractPersons).via(extractDates)

  val staticResources =
    get {
      (pathEndOrSingleSlash & redirectToTrailingSlashIfMissing(StatusCodes.TemporaryRedirect)) {
        getFromResource("public/index.html")
      } ~ {
        getFromResourceDirectory("public")
      }
    }

  val route = path("image" / "ocr") {
    post {
      fileUpload("fileUpload") {
        case (_, fileStream) =>
          val inputStream = fileStream.runWith(StreamConverters.asInputStream())
          val image = ImageIO.read(inputStream)
          val ocr = Source.single(image).via(imagePreProcessFlow).via(ocrFlow)

          complete(ocr)
      }
    }
  } ~ staticResources

  Http().bindAndHandle(route, "localhost", 8080)
}

case class OcrSuggestions(ocr:String, suggestions: Set[Map[String, List[String]]])
case class OcrSuggestionsPersons(ocr:String, suggestions: Set[Map[String, List[String]]], persons: List[String])
case class OcrSuggestionsPersonsDates(ocr:String, suggestions: Set[Map[String, List[String]]], persons: List[String], dates: List[(List[String], String)])

trait OCR {
  lazy val tesseract: Tesseract = new Tesseract
  tesseract.setDatapath("/usr/local/Cellar/tesseract/4.1.1/share/tessdata/")
}

trait Spell {
  import com.atlascopco.hunspell.Hunspell
  lazy val speller = new Hunspell("src/main/resources/en_US.dic", "src/main/resources/en_US.aff")
}

trait NLP {
  lazy val tokenModel = new TokenizerModel(getClass.getResourceAsStream("/en-token.bin"))
  lazy val tokenizer = new TokenizerME(tokenModel);

  lazy val sentenceModel = new SentenceModel(getClass.getResourceAsStream("/en-sent.bin"))
  lazy val sentenceDetector = new SentenceDetectorME(sentenceModel);

  lazy val personModel = new TokenNameFinderModel(getClass.getResourceAsStream("/en-ner-person.bin"))
  lazy val personFinderME = new NameFinderME(personModel);
}

trait Natty {
  lazy val parser = new Parser()
}

object MyJsonProtocol
  extends SprayJsonSupport
    with DefaultJsonProtocol {
  implicit val ocrFormat: RootJsonFormat[OcrSuggestions] = jsonFormat2(OcrSuggestions.apply)
  implicit val ocr2Format: RootJsonFormat[OcrSuggestionsPersons] = jsonFormat3(OcrSuggestionsPersons.apply)
  implicit val ocr3Format: RootJsonFormat[OcrSuggestionsPersonsDates] = jsonFormat4(OcrSuggestionsPersonsDates.apply)
}
