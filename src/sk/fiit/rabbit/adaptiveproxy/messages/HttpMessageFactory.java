package sk.fiit.rabbit.adaptiveproxy.messages;

import java.net.InetSocketAddress;

import sk.fiit.rabbit.adaptiveproxy.headers.RequestHeader;
import sk.fiit.rabbit.adaptiveproxy.headers.ResponseHeader;

/**
 * A HTTP message factory is an entity, that could serve proxy plugins as a builder
 * of HTTP messages that are valid according to standards defined in
 * <a href="http://www.w3.org/Protocols/rfc2616/rfc2616.html">RFC2616</a>.
 * @author <a href="mailto:redeemer.sk@gmail.com">Jozef Tomek</a>
 *
 */
public interface HttpMessageFactory {
	/**
	 * Returns constructed HTTP request based on copy of passed HTTP request header
	 * <code>baseHeader</code> (if not <code>null</code>). If <code>contentType</code>
	 * is not <code>null</code>, constructed request will be able to hold body data 
	 * (initialized to empty byte array) and <i>Content-Type</i> header field will be
	 * set this value. Parameter <code>clientSocket</code> is used to set client's 
	 * connection endpoint info of constructed response to existing value. 
	 * @param clientSocket socket representing connection's endpoint on the client's side,
	 * may be null 
	 * @param baseHeader HTTP request header to base constructed message on, may be null
	 * @param contentType <i>Content-Type</i> header field value if request with body wanted,
	 * may be null
	 * @return constructed HTTP request message
	 */
	ModifiableHttpRequest constructHttpRequest(InetSocketAddress clientSocket, RequestHeader baseHeader,  String contentType);
	
	/**
	 * Returns constructed HTTP response based on copy of passed HTTP response header
	 * <code>baseHeader</code> (if not null). If <code>contentType</code>
	 * is not <code>null</code>, constructed response will be able to hold body data 
	 * (initialized to empty byte array) and <i>Content-Type</i> header field will be
	 * set this value.
	 * @param baseHeader HTTP response header to base constructed message on, may be null 
	 * @param contentType <i>Content-Type</i> header field value if response with body wanted,
	 * may be null
	 */
	ModifiableHttpResponse constructHttpResponse(ResponseHeader baseHeader,  String contentType);
}
