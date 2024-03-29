package rabbit.httpio.request;

import rabbit.io.BufferHandle;

/** A listener for client resource data (POST:ed content).
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public interface ClientResourceListener {
    void resourceDataRead (BufferHandle bufHandle);
}
