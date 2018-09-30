# Test Crawler-Commons' Sitemap Parser

Test performance of [crawler-commons](https://github.com/crawler-commons/crawler-commons)' SiteMapParser. Sitemaps are read from WARC files to make results reproducible.

0. compile and install crawler-commons and this project:

  ```
  git clone  https://github.com/crawler-commons/crawler-commons.git
  cd crawler-commons/
  mvn install
  cd -
  mvn package
  ```

  You may also change the crawler-commons version dependency in the pom.xml to test another crawler-commons version.

1. prepare a list of URLs pointing to sitemap files, e.g.

  ```
  echo "https://www.sitemaps.org/sitemap.xml" >sitemaps.txt
  ```

2. fetch the sitemaps and wrap them into a WARC file, e.g, using [wget](https://www.gnu.org/software/wget/):

  ```
  wget --no-warc-keep-log \
       --warc-file sitemaps \
       -O /dev/null \
       -i sitemaps.txt
  ```

3. test the sitemap parser, e.g.,

  ```
  ./run.sh -Dwarc.index=false \
           -Dsitemap.strict=false \
           -Dsitemap.partial=true \
           -Dsitemap.lazyNamespace=true \
           -Dsitemap.extensions=true \
           sitemaps.warc.gz
  ```

4. set properties to modify the tests
  - `sitemap.strict` (if true) check URLs whether they share the prefix (protocol, host, port, path without filename) with the sitemap URL
  - `sitemap.partial` (if true) relax the parser to return also a parsed sitemap if the document is truncated or broken and was only partially parsed
  - `sitemap.strictNamespace` (if true) check sitemap namespaces, ignore XML elements which are not in the `http://www.sitemaps.org/schemas/sitemap/0.9` namespace
  - `sitemap.lazyNamespace` (if true) check namespace but allow legacy namespaces
  - `sitemap.extensions` (if true) enable support for sitemap extensions (news, image, video, etc.)
  - `warc.index` (if true) read the WARC file(s) ahead and index the records in a Map <url,record>. This causes some overhead in CPU time and memory but allows to parse sitemap indexes recursively.
  - `warc.parse.url` parse a single sitemap identified by URL.
