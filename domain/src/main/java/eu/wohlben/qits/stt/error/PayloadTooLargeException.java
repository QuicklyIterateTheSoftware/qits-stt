package eu.wohlben.qits.stt.error;

/** Domain error mapped to HTTP 413 by the web layer. */
public class PayloadTooLargeException extends DomainException {

  public PayloadTooLargeException(String message) {
    super(413, message);
  }
}
