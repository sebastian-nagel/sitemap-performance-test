# Test Crawler-Commons' Sitemap Parser

Test performance of [crawler-commons](https://github.com/crawler-commons/crawler-commons)' SiteMapParser. Sitemaps are read from WARC files to make results reproducible.

0. compile and install crawler-commons (0.8-SNAPSHOT) and this project:

  ```
  git clone  https://github.com/crawler-commons/crawler-commons.git
  cd rawler-commons/
  mvn install
  cd -
  mvn package
  ```

  Alternatively, change the crawler-commons version dependency in the pom.xml

1. prepare a list of URLs pointing to sitemap files, e.g.

  ```
  echo "https://www.sitemaps.org/sitemap.xml" >sitemaps.txt
  ```

2. fetch the sitemaps and wrap them into a WARC file

  ```
  wget --no-warc-keep-log \
       --warc-file sitemaps \
       -O /dev/null \
       -i sitemaps.txt
  ```

3. test the sitemap parser

  ```
  ./run.sh -Dwarc.index=false \
           -Dsitemap.useSax=true \
           -Dsitemap.strict=false \
           -Dsitemap.partial=true \
           sitemaps.warc.gz
  ```

4. set properties to modify the tests
   - `sitemap.useSax` use the SAX parser instead of the DOM parser
   - 