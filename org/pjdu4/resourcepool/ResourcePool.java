package org.pjdu4.resourcepool;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Lock;
import java.util.ArrayList;

/**
 * 
 * @author pjdu4
 * 
 * Here are comments supplemental to the design document given for this
 * coding exercise:
 * 
 * - (1) There were (in my understanding) a couple of interpretations for what
 *       add() might mean when given the same resource more than once.  I chose
 *       the more generous (and harder) one. See README.txt for more details.
 * 
 * - (2) A Resource is assumed to be a non-null entity.  Therefore, the add
 *       and remove methods return false if it is given a null Resource.
 * 
 * - (3) It might be desirable for subclasses of ResourcePool to have many
 *       extensions, such as having an add() variation which doesn't make the
 *       assumption I mentioned in item (1), or having a close() variation which
 *       takes a TimeUnit.  For now, we'll make this class final until future
 *       enhancement requests can be clarified with the client.
 *
 */

public final class ResourcePool {
	
    protected boolean isOpen = false;

    protected Lock Rlock = new ReentrantLock();

    // this Condition is signaled either when an action may have emptied
    // this.unAcquired, or isOpen is set to true
    protected Condition unAcquiredMaybeNotEmptyOrPoolOpened = Rlock.newCondition();

    // this Condition is signaled when anything is removed from this.acquired
    protected Condition acquiredMayBeEmpty = Rlock.newCondition();

    // this Condition is signaled when a resource is released
    protected Condition neededResourceMayBeReleased = Rlock.newCondition();
    
    // <code>acquired</code> contains all objects which are in the pool which
    // have been accquired by someone via an acquire(...) call.
    //
    // We follow the pattern of adding them to the beginning and removing them
    // from the end, so that objects get used "fairly".
    protected ArrayList<R> acquired = new ArrayList<R>();


    // <code>unAcquired</code> contains all objects which are in the pool but
    // which aren't currently acquired.
    //
    // We follow the pattern of adding them to the beginning and removing them
    // from the end, so that objects get used "fairly".
    protected ArrayList<R> unAcquired = new ArrayList<R>();

    /*
     * If the pool is closed, then open it, and alert any Threads which might'be
     * been waiting.
     *
     */
    public void open() {
	Rlock.lock();
	try {
	    if (!isOpen) {
	      this.isOpen=true;
	      unAcquiredMaybeNotEmptyOrPoolOpened.signalAll();
	    }
	} finally {
	    Rlock.unlock();
	}
    }

    /*
     * returns true if the pool is open
     */

    public boolean isOpen() {
	Rlock.lock();
	try {
	    return isOpen;
	} finally {
	    Rlock.unlock();
	}
    }
	
    /*
     * close the pool, without waiting for acquired objects to be returned
     */

    public void closeNow() {
	Rlock.lock();
	try {
	    if (isOpen) {
		isOpen=false;
	    }
	} finally {
	    Rlock.unlock();
	}
    }

    /*
     * acquire and return a Resource, or block until we can
     */ 
	
    public R acquire() throws InterruptedException {
	Rlock.lock();
	try {
	    while (!isOpen || unAcquired.isEmpty()) {
		unAcquiredMaybeNotEmptyOrPoolOpened.await();
	    }

	    R retval = unAcquired.remove(unAcquired.size()-1);
	    acquired.add(retval);
	    return retval;
	} finally {
	    Rlock.unlock();
	}
    }

    /*
     * Acquire and return a resource, blocking if there are none for at least
     * the the stipulated time.
     *
     * Returns null if we were unable to find a Resource in the stipulated time.
     */

    public R acquire(long timeout, TimeUnit timeUnit)
	throws InterruptedException {

	// modelled after JDK 1.7's recommendations on the most robust implementation.
	// (http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/locks/Condition.html)
	// avoids a common problem if the start of the timeout period being reset
	// upon false or unhelpful Condition signals

	Rlock.lock();
	try {
	    long nanos = timeUnit.toNanos(timeout);
	    while (!isOpen || unAcquired.isEmpty()) {
		if (nanos <= 0L) {
		    return null;
		}
		nanos = unAcquiredMaybeNotEmptyOrPoolOpened.awaitNanos(nanos);
	    }

	    if (!isOpen) {
		return null;
	    }

	    if (unAcquired.isEmpty()) {
		return null;
	    }

	    return unAcquired.remove(unAcquired.size()-1);
	} finally {
	    Rlock.unlock();
	}
    }


    /* 
     * Add the given Resource to the pool.  Return false if the argument is null,
     * else true;
     *
     */
	
    public boolean add(R r) {
	Rlock.lock();
	try {
	    if (r==null) return false;

	    unAcquired.add(r);
            unAcquiredMaybeNotEmptyOrPoolOpened.signalAll();
	    return true;
	} finally {
	    Rlock.unlock();
	}
    }
	
	/*
	 * return the resource to the pool; we don't need it any more!
	 */
	
    /*
     * Release the resource, so that it may be used by other Threads.
     * Signal other threads which may have been waiting for the resource.
     *
     */

    public void release(R r) {
	Rlock.lock();
	try {
	    unAcquired.add(r);
	    acquired.remove(r);

	    unAcquiredMaybeNotEmptyOrPoolOpened.signalAll();
	    neededResourceMayBeReleased.signalAll();
	    if (acquired.isEmpty()) {
		acquiredMayBeEmpty.signalAll();
	    }
	} finally {
	    Rlock.unlock();
	}
    }
	
    /*
     * Remove the resource, without waiting for it to be released.
     */

    public boolean removeNow(R r) {
	Rlock.lock();
	try {
	    if (r==null) return false;

	    if (unAcquired.remove(r)) {
		return true;
	    }

	    boolean retval = acquired.remove(r);
	    if (acquired.isEmpty()) {
		acquiredMayBeEmpty.signalAll();
	    }
	    return retval;
	} finally {
	    Rlock.unlock();
	}
    }

    /*
     * Block if the pool is open and there are any acquired resources not yet
     * released.  Else simply close the pool.
     */

    public void close() throws InterruptedException {
	Rlock.lock();
	try {
	    if (!isOpen) {
		return;
	    }

	    // we're waiting while the pool is open but there're items unacquired
	    while (!acquired.isEmpty()) {
		acquiredMayBeEmpty.await();
	        // Rlock.wait();
	    }

	    isOpen=false;
	} finally {
	    Rlock.unlock();
	}
    }

    /*
     * Block if the resource is acquired and not yet released.  Else simply
     * remove the Resource from the pool.
     */

    public boolean remove(R r) throws InterruptedException {
	Rlock.lock();
	try {
	    while (true) {
		if (unAcquired.contains(r)) {
		    unAcquired.remove(r);
		    // Rlock.notifyAll();
		    return true;
		}

		// if we don't have the Resource in any manner...
		if (!acquired.contains(r)) {
		    return false;
		}

		while (acquired.contains(r) && !unAcquired.contains(r)) {
		    neededResourceMayBeReleased.await();
		}

		// so now, it's either been added to unAcquired, or it's no
                // longer in acquired, because someone else got it, so try again!
	    }

	} finally {
	    Rlock.unlock();
	}
    }
}
