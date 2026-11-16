package com.afrotransact.ai.port;

import java.util.List;

import com.afrotransact.ai.port.AccountReader.BookingSummary;
import com.afrotransact.ai.port.AccountReader.Result;

/** The shopper's service bookings (bookings {@code GET /v1/bookings/mine}); one edge, ai-gateway -> bookings. */
public interface BookingHistory {

    Result<List<BookingSummary>> mine(Shopper shopper);
}
