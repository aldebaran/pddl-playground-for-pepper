package com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk

import android.content.Context
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.conversation.*
import com.aldebaran.qi.sdk.builder.TopicBuilder
import com.aldebaran.qi.sdk.util.IOUtils
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Helper to create a topic from a resource.
 */
suspend fun createTopicFromResource(context: Context, qiContext: QiContext, resource: Int): Topic {
    return TopicBuilder.with(qiContext)
        .withText(IOUtils.fromRaw(context, resource))
        .buildAsync().await()
}

/**
 * Helper to create a topics from resources.
 */
suspend fun createTopicsFromResources(context: Context, qiContext: QiContext, vararg resources: Int): List<Topic> {
    return resources.map {
        val topic = TopicBuilder.with(qiContext)
            .withText(IOUtils.fromRaw(context, it))
            .buildAsync().await()
        val name = topic.async().name.await()
        Timber.d("Created topic \"$name\"")
        topic
    }
}

/**
 * Helper to create a topic from a list of resources files.
 * The content of all the topics will be concatenated.
 */
fun createTopicFromResources(context: Context, qiContext: QiContext, resources: List<Int>): Topic {
    var mergedTopicName = ""
    var mergedTopicContent = ""
    for (resource in resources) {
        val topicText = IOUtils.fromRaw(context, resource)
        val endOfFirstLine = topicText.indexOf('\n')
        if (endOfFirstLine == -1)
            break
        val beforeBeginOfTopicName = topicText.indexOf('~')
        val endOfTopicName = topicText.indexOf('(', beforeBeginOfTopicName)
        if (beforeBeginOfTopicName == -1 || endOfTopicName == -1)
            break
        if (mergedTopicName.isNotEmpty()) {
            mergedTopicName += "_"
            mergedTopicContent += "\n"
        }
        mergedTopicName += topicText.substring(beforeBeginOfTopicName + 1, endOfTopicName)
        mergedTopicContent += topicText.substring(endOfFirstLine + 1)
    }
    return TopicBuilder.with(qiContext)
        .withText("topic: ~${mergedTopicName}()\n$mergedTopicContent")
        .build()
}

/**
 * Helper to create a topic from a resource.
 */
fun createTopicFromResource(context: Context, qiContext: QiContext, resourceName: String): Topic {
    return TopicBuilder.with(qiContext)
        .withText(
            (context.javaClass::class.java.getResource(resourceName)
                ?: throw RuntimeException("$resourceName does not exist!")).readText()
        )
        .build()
}

/**
 * Creates the name of a topic created on the fly,
 * based on a name of an action accepting choice parameters, and an index.
 * @param actionName The name of an action constructed from a template that accept choice parameters.
 * @param index The index of the choice parameter the topic name would correspond to.
 */
fun createChoiceTopicName(actionName: String, index: Int): String {
    return "${actionName}_choice_$index"
}

/**
 * Creates a topic from any QiChat rule, including a single phrase (a human-speakable text).
 * @param topicName The name to give to the topic, containing only ASCII alphanumerical characters or "_".
 * @param qiChatRule A human-readable phrase or a QiChatRule.
 */
fun createTopicContentFromPhrase(topicName: String, qiChatRule: String): String {
    return "topic: ~$topicName()\nu:($qiChatRule) %finished"
}

/**
 * Creates the name of a topic created on the fly,
 * based on a name of an action accepting choice parameters, and an index.
 * @param actionName The name of an action constructed from a template that accept choice parameters.
 * @param index The index of the choice parameter the topic name would correspond to.
 */
suspend fun createChoiceTopic(
    qiContext: QiContext, actionName: String, index: Int, qiChatRule: String
): Topic {
    val topicName = createChoiceTopicName(actionName, index)
    val topicContent = createTopicContentFromPhrase(topicName, qiChatRule)
    return TopicBuilder.with(qiContext).withText(topicContent).buildAsync().await()
}

/**
 * Enable or disable a list of topics.
 */
suspend fun setTopicsEnabled(qiChatbot: QiChatbot, topics: Collection<Topic>, enabled: Boolean) {
    val topicStatuses = topics.map { qiChatbot.async().topicStatus(it) }
        .map { it.await() }
    return setTopicsEnabled(topicStatuses, enabled)
}

/**
 * Enable or disable a list of topic statuses.
 */
private suspend fun setTopicsEnabled(topicStatuses: Collection<TopicStatus>, enabled: Boolean) {
    topicStatuses.map { it.async().setEnabled(enabled).await() }
}

/**
 * Enable a list of topics and return a disposable to disable them.
 */
fun enableTopics(qiChatbot: QiChatbot, topics: List<Topic>): Disposable {
    Timber.d("Enabling topics ${topics.map { it.name }}")
    runBlocking { setTopicsEnabled(qiChatbot, topics, true) }
    return disposableOf {
        Timber.d("Disabling topics ${topics.map { it.name }}")
        runBlocking { setTopicsEnabled(qiChatbot, topics, false) }
    }
}

/**
 * Enable a list of topics and return a disposable to disable them.
 */
suspend fun enableTopicsSuspend(qiChatbot: QiChatbot, topics: List<Topic>): DisposableSuspend {
    Timber.d("Enabling topics ${topics.map { it.name }}")
    setTopicsEnabled(qiChatbot, topics, true)
    return disposableSuspendOf {
        Timber.d("Disabling topics ${topics.map { it.name }}")
        setTopicsEnabled(qiChatbot, topics, false)
    }
}

/**
 * Enable a topic and return a disposable to disable it.
 */
suspend fun enableTopic(qiChatbot: QiChatbot, topic: Topic): Disposable {
    Timber.d("Enabling topic ${topic.name}")
    qiChatbot.topicStatus(topic)?.async()?.setEnabled(true)?.await()
    return disposableOf {
        Timber.d("Disabling topic ${topic.name}")
        qiChatbot.topicStatus(topic)?.enabled = true
    }
}


/**
 * Disable a list of topics and return a disposable to enable them.
 */
fun disableTopics(qiChatbot: QiChatbot, topics: Collection<Topic>): Disposable {
    runBlocking { setTopicsEnabled(qiChatbot, topics, false) }
    return disposableOf {
        runBlocking { setTopicsEnabled(qiChatbot, topics, true) }
    }
}


/**
 * Subscribe to a bookmark in a topic.
 */
fun subscribeToBookmark(
    qiChatbot: QiChatbot,
    topic: Topic,
    bookmarkName: String,
    callback: () -> Unit
): Disposable {
    val bookmarkStatus =
        topic.async().bookmarks.andThenCompose {
            val bookmark = it[bookmarkName]
            if (bookmark == null) {
                Future.of<BookmarkStatus?>(null)
            } else {
                qiChatbot.async().bookmarkStatus(bookmark)
                    .andThenApply { bookmarkStatus: BookmarkStatus ->
                        bookmarkStatus.addOnReachedListener(callback)
                        bookmarkStatus
                    }
            }
        }

    return disposableOf {
        bookmarkStatus.andThenApply { bs ->
            bs?.async()?.removeAllOnReachedListeners()
        }
    }
}


/**
 * Subscribe to a bookmark in a list of topics.
 */
fun subscribeToBookmark(
    qiChatbot: QiChatbot,
    topics: List<Topic>,
    bookmarkName: String,
    callback: () -> Unit
): Disposable {
    val disposables = Disposables()
    topics.forEach { topic ->
        disposables.add(subscribeToBookmark(qiChatbot, topic, bookmarkName, callback))
    }
    return disposables
}


/**
 * Wait for a bookmark.
 */
fun waitForBookmark(qiChatbot: QiChatbot, topics: List<Topic>, bookmarkName: String): Future<Void> {
    val p = Promise<Void>()
    p.setOnCancel { it.setCancelled() }
    val subscription = subscribeToBookmark(qiChatbot, topics, bookmarkName) {
        try {
            p.setValue(null)
        } catch (t: Throwable) {
            Timber.d("Bookmark \"$bookmarkName\" reached anew before auto-unsubscription")
        }
    }
    return p.future.thenCompose {
        subscription.dispose()
        it
    }
}

/**
 * Waits asynchronously for a Qi Chatbot to reach a bookmark in a topic.
 */
suspend fun waitForBookmark(
    qiChatbot: QiChatbot,
    topic: Topic,
    bookmarkName: String
) {
    withDisposablesSuspend { disposables ->
        val bookmarkReached = CompletableDeferred<Unit>()
        val bookmark = topic.async().bookmarks.await()[bookmarkName]
        if (bookmark != null) {
            val bookmarkStatus = qiChatbot.async().bookmarkStatus(bookmark).await()
            val onReachedListener = BookmarkStatus.OnReachedListener {
                bookmarkReached.complete(Unit)
            }
            bookmarkStatus.async().addOnReachedListener(onReachedListener).await()
            disposables.add {
                bookmarkStatus.async().removeOnReachedListener(onReachedListener).await()
            }
            bookmarkReached.await()
        } else {
            val topicName = topic.async().name.await()
            throw RuntimeException("no such bookmark \"$bookmarkName\" in topic \"$topicName\"")
        }
    }
}

/**
 * Wait for the finished bookmark to be triggered.
 */
fun waitForFinishedBookmark(qiChatbot: QiChatbot, topics: List<Topic>): Future<Void> {
    return waitForBookmark(qiChatbot, topics, "finished")
}

suspend fun waitForStartedBookmark(qiChatbot: QiChatbot, topic: Topic) =
    waitForBookmark(qiChatbot, topic, "started")

suspend fun waitForFinishedBookmark(qiChatbot: QiChatbot, topic: Topic) =
    waitForBookmark(qiChatbot, topic, "finished")

suspend fun goToBookmark(qiChatbot: QiChatbot, topic: Topic, bookmarkName: String) =
    goToBookmark(qiChatbot, listOf(topic), bookmarkName)

//topic.bookmarks[bookmarkName] is a call to naoqi, it takes time, just like {Bookmark}.name
//TODO fix like Valkyrie, store a list of Bookmarks per Topic at the start, it'll GREATLY improve your reaction/go to bookmark time.

suspend fun goToBookmark(qiChatbot: QiChatbot, topics: List<Topic>, bookmarkName: String) {
    Timber.d("Looking for bookmark $bookmarkName in ${topics.size} topics")
    for (topic in topics) {
        val bookmarks = topic.async().bookmarks.await()
        val bookmark = bookmarks[bookmarkName]
        if (bookmark != null) {
            Timber.d("Found bookmark $bookmarkName in topic ${topic.name}")
            qiChatbot.async().goToBookmark(
                bookmark,
                AutonomousReactionImportance.LOW,
                AutonomousReactionValidity.DELAYABLE
            ).await()
        } else {
            Timber.d("No bookmark $bookmarkName in topic ${topic.name}")
        }
    }
    Timber.d("Out of goto bookmark")
}

suspend fun goToStartBookmark(qiChatbot: QiChatbot, topics: List<Topic>) =
    goToBookmark(qiChatbot, topics, "start")

suspend fun goToStartBookmark(qiChatbot: QiChatbot, topic: Topic) =
    goToBookmark(qiChatbot, listOf(topic), "start")
