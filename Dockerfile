FROM hseeberger/scala-sbt:8u222_1.3.5_2.13.1
WORKDIR /app
RUN   mkdir logs
RUN   mkdir lib
RUN   mkdir web_root
RUN   mkdir web_root/zio_doc 
RUN   cd web_root/zio_doc; curl -fsL https://javadoc.io/jar/dev.zio/zio_2.13/1.0.3/zio_2.13-1.0.3-javadoc.jar.jar | jar -x;cd .. 
RUN   cd /app
COPY  /target/scala-2.13/zio-http_2.13-0.0.1.jar .
COPY  /lib_managed/jars/dev.zio/zio_2.13/zio_2.13-1.0.3.jar ./lib
COPY  /lib_managed/jars/dev.zio/izumi-reflect_2.13/izumi-reflect_2.13-1.0.0-M9.jar ./lib
COPY  /lib_managed/jars/dev.zio/izumi-reflect-thirdparty-boopickle-shaded_2.13/izumi-reflect-thirdparty-boopickle-shaded_2.13-1.0.0-M9.jar ./lib
COPY  /lib_managed/jars/dev.zio/zio-stacktracer_2.13/zio-stacktracer_2.13-1.0.3.jar ./lib
COPY  /lib_managed/jars/com.propensive/magnolia_2.13/magnolia_2.13-0.17.0.jar ./lib
COPY  /lib_managed/jars/com.propensive/mercator_2.13/mercator_2.13-0.2.1.jar  ./lib
COPY  /lib_managed/jars/dev.zio/zio-json_2.13/zio-json_2.13-0.0.1.jar ./lib
COPY  /lib_managed/jars/dev.zio/zio-streams_2.13/zio-streams_2.13-1.0.3.jar ./lib

COPY  keystore.jks .
COPY  start.sh .
RUN   chmod +x start.sh
CMD   ./start.sh 