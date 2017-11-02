package com.github.tamales.impls

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl._

import okhttp3.OkHttpClient

object UnsafeOkHttpClientBuilder {
  private val passtrough = new X509TrustManager {
    override def getAcceptedIssuers = Array[X509Certificate]()

    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String) = ()
    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String) = ()
  }
  private lazy val factory = {
    val ssl = SSLContext.getInstance("SSL")
    ssl.init(null, Array[TrustManager](passtrough), new SecureRandom())
    ssl.getSocketFactory
  }

  /** @return an OkHttpClient that does not verify the SSL certificates.
    *         Use with caution but required for self signed certificates
    */
  def build():OkHttpClient = new OkHttpClient.Builder()
      .sslSocketFactory(factory, passtrough)
      .hostnameVerifier((host: String, session: SSLSession) => true)
      .build()

}
