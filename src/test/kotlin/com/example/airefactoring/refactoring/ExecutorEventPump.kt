package com.example.airefactoring.refactoring

import com.intellij.testFramework.dispatchAllEventsInIdeEventQueue
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs a suspend executor off the test (EDT) thread while pumping the IDE event queue.
 *
 * Calling `runBlocking` directly on the EDT deadlocks against any executor whose resumption is
 * dispatched to `Dispatchers.EDT`: the EDT parks inside `runBlocking` and stops pumping the AWT
 * queue the resumed continuation was posted to. Established by the accepted Extract Delegate
 * executor test and reused by the Replace Inheritance with Delegation executor test.
 */
fun <T> runExecutorOffEdt(block: suspend () -> T): T {
    val pool = Executors.newSingleThreadExecutor()
    return try {
        val f = pool.submit<T> { runBlocking { block() } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline && !f.isDone) {
            dispatchAllEventsInIdeEventQueue()
            Thread.sleep(1)
        }
        try {
            f.get(1, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    } finally {
        pool.shutdownNow()
    }
}