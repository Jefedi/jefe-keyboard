package ovh.jefe.keyboard

sealed interface RemoteResult<out T> {
    data class Success<T>(val value: T) : RemoteResult<T>

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : RemoteResult<Nothing>
}
