package com.umair.purpose.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.LocalDate

/** Fresh on subscription; updates overnight and after clock/zone changes without needing a DB write. */
fun calendarDays(today: () -> LocalDate = { LocalDate.now() }): Flow<LocalDate> = flow {
    while (true) {
        emit(today())
        delay(30_000)
    }
}.distinctUntilChanged()
