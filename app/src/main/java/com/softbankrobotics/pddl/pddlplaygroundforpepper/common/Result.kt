package com.softbankrobotics.pddl.pddlplaygroundforpepper.common

/**
 * Result class copied from Kotlin 1.3's Result.
 * Since the standard API is not stable yet, Results cannot be assigned to object members,
 * nor be returned by functions.
 * This copy supports these usages.
 */
sealed class Result<T> {
    /**
     * Returns true if this instance represents a failed outcome.
     * In this case isSuccess returns false.
     */
    val isFailure: Boolean get() = this is Failure<T>

    /**
     * Returns true if this instance represents a successful outcome.
     * In this case isFailure returns false.
     */
    val isSuccess: Boolean get() = this is Success<T>

    /**
     * Returns the encapsulated Throwable exception if this instance represents failure
     * or null if it is success.
     */
    fun exceptionOrNull(): Throwable? = if (this is Failure<T>) this.exception else null

    /**
     * Returns the encapsulated value if this instance represents success or null if it is failure.
     */
    fun getOrNull(): T? = if (this is Success<T>) this.value else null

    /**
     * Returns a string Success(v) if this instance represents success
     * where v is a string representation of the value
     * or a string Failure(x) if it is failure
     * where x is a string representation of the exception.
     */
    abstract override fun toString(): String

    abstract override fun equals(other: Any?): Boolean
    abstract override fun hashCode(): Int

    companion object {
        /**
         * Returns an instance that encapsulates the given Throwable as failure.
         */
        fun <T> failure(exception: Throwable): Result<T> = Failure(exception)

        /**
         * Returns an instance that encapsulates the given value as successful value.
         */
        fun <T> success(value: T): Result<T> = Success(value)
    }
}

/**
 * A result representing a failure.
 * Prefer using Result<T>.failure instead, for easier migration to next Kotlin standard.
 */
class Failure<T>(val exception: Throwable) : Result<T>() {
    override fun toString(): String = "Failure($exception)"

    override fun equals(other: Any?): Boolean =
        if (other is Failure<*>)
            exception == other.exception
        else false

    override fun hashCode(): Int {
        var result = true.hashCode()
        result = 31 * result + exception.hashCode()
        return result
    }
}

/**
 * A result representing a success.
 * Prefer using Result<T>.success instead, for easier migration to next Kotlin standard.
 */
class Success<T>(val value: T) : Result<T>() {
    override fun toString(): String = "Success($value)"

    override fun equals(other: Any?): Boolean =
        if (other is Success<*>)
            value == other.value
        else false

    override fun hashCode(): Int {
        var result = false.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }
}

/**
 * Returns the result of onSuccess for the encapsulated value if this instance represents success
 * or the result of onFailure function for the encapsulated Throwable exception if it is failure.
 */
inline fun <R, T> Result<T>.fold(
    onSuccess: (value: T) -> R,
    onFailure: (exception: Throwable) -> R
): R {
    return when (this) {
        is Success -> onSuccess(this.value)
        is Failure -> onFailure(this.exception)
    }
}

/**
 * Returns the encapsulated value if this instance represents success
 * or the defaultValue if it is failure.
 */
fun <R, T : R> Result<T>.getOrDefault(defaultValue: R): R = fold({ it }, { defaultValue })

/**
 * Returns the encapsulated value if this instance represents success
 * or the result of onFailure function for the encapsulated Throwable exception if it is failure.
 */
inline fun <R, T : R> Result<T>.getOrElse(onFailure: (exception: Throwable) -> R): R =
    fold({ it }, { onFailure(it) })

/**
 * Returns the encapsulated value if this instance represents success
 * or throws the encapsulated Throwable exception if it is failure.
 */
fun <T> Result<T>.getOrThrow(): T = fold({ it }, { throw(it) })

/**
 * Returns the encapsulated result of the given transform function applied to
 * the encapsulated value if this instance represents success
 * or the original encapsulated Throwable exception if it is failure.
 */
inline fun <R, T> Result<T>.map(transform: (value: T) -> R): Result<R> =
    fold({ Result.success(transform(it)) }, { Result.failure(it) })


/**
 * Returns the encapsulated result of the given transform function applied to
 * the encapsulated value if this instance represents success
 * or the original encapsulated Throwable exception if it is failure.
 */
inline fun <R, T> Result<T>.mapCatching(transform: (value: T) -> R): Result<R> =
    fold({ value -> resultOf { transform(value) } }, { Result.failure(it) })

/**
 * Performs the given action on the encapsulated Throwable exception
 * if this instance represents failure.
 * Returns the original Result unchanged.
 */
inline fun <T> Result<T>.onFailure(action: (exception: Throwable) -> Unit): Result<T> {
    if (this is Failure)
        action(this.exception)
    return this
}

/**
 * Performs the given action on the encapsulated value
 * if this instance represents success.
 * Returns the original Result unchanged.
 */
inline fun <T> Result<T>.onSuccess(action: (value: T) -> Unit): Result<T> {
    if (this is Success)
        action(this.value)
    return this
}

/**
 * Returns the encapsulated result of the given transform function applied to
 * the encapsulated Throwable exception if this instance represents failure
 * or the original encapsulated value if it is success.
 */
inline fun <R, T : R> Result<T>.recover(transform: (exception: Throwable) -> R): Result<R> =
    fold({ Result.success(it) }, { Result.success(transform(it)) })

/**
 * Returns the encapsulated result of the given transform function applied to
 * the encapsulated Throwable exception if this instance represents failure
 * or the original encapsulated value if it is success.
 */
inline fun <R, T : R> Result<T>.recoverCatching(transform: (exception: Throwable) -> R): Result<R> =
    fold({ Result.success(it) }, { resultOf { transform(it) } })

/**
 * Computes a non-throwing result out of the given block.
 */
inline fun <T> resultOf(block: () -> T): Result<T> {
    return try {
        Result.success(block.invoke())
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

/**
 * Computes a result from a nullable value.
 * The result represents a success if the value is not null
 * or a failure if the value is null.
 */
fun <T> resultOfNullable(value: T?): Result<T> {
    return if (value != null)
        Result.success(value)
    else
        Result.failure(NullPointerException())
}
