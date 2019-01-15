## Up and running | Mac OS

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