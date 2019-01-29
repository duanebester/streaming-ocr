## Up and running | Mac OS

[Blog Post](https://towardsdatascience.com/ocr-with-akka-tesseract-and-javacv-part-1-702781fc73ca)

```
brew install tesseract
```

And then I set an Environment Var `LC_ALL=C` in IntelliJ Run Configuration

```
sbt run
```

Example cURL

```
curl -X POST -F 'fileUpload=@/Users/duanebester/Documents/input.jpg' 'http://localhost:8080/image/ocr'
```