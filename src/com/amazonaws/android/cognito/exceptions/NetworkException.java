package com.amazonaws.android.cognito.exceptions;

/**
 * This exception is thrown when a service request failed due to network
 * connectivity problem.
 */
public class NetworkException extends DataStorageException {

    private static final long serialVersionUID = 8685123233927843893L;

    public NetworkException() {
        super();
    }

    public NetworkException(String detailMessage, Throwable throwable) {
        super(detailMessage, throwable);
    }

    public NetworkException(String detailMessage) {
        super(detailMessage);
    }

    public NetworkException(Throwable throwable) {
        super(throwable);
    }

}
