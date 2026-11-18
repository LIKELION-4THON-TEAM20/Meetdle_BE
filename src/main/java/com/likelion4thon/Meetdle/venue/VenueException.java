package com.likelion4thon.Meetdle.venue;

public class VenueException extends RuntimeException {
    final int status;

    public VenueException(int status, String code) {
        super(code);
        this.status = status;
    }
}
