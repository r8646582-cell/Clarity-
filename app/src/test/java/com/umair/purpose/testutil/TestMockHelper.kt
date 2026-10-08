package com.umair.purpose.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.mockito.Mockito

inline fun <reified T : Any> smartMock(): T {
    return Mockito.mock(T::class.java) { invocation ->
        val returnType = invocation.method.returnType
        when {
            StateFlow::class.java.isAssignableFrom(returnType) -> MutableStateFlow<Any?>(null)
            Flow::class.java.isAssignableFrom(returnType) -> emptyFlow<Any>()
            List::class.java.isAssignableFrom(returnType) -> emptyList<Any>()
            Set::class.java.isAssignableFrom(returnType) -> emptySet<Any>()
            Map::class.java.isAssignableFrom(returnType) -> emptyMap<Any, Any>()
            returnType == String::class.java -> ""
            else -> Mockito.RETURNS_DEEP_STUBS.answer(invocation)
        }
    }
}
