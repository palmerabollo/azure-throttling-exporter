package com.baikalplatform.azure;

public class MetricsException extends Exception {
    public MetricsException(String msg) {
        super(msg);
    }

    public MetricsException(String msg, Throwable t) {
        super (msg, t);
    }
}
