package eu.zderadicka.audioserve.net


import eu.zderadicka.audioserve.utils.copyFile
import java.io.*
import java.util.HashSet
import kotlin.properties.Delegates

const val UNKNOWN_LENGTH: Long = -1L

internal const val TMP_FILE_SUFFIX = ".\$TMP\$"

class CacheItem(val path: String, val cacheDir: File, changeListener: Listener? = null) : Closeable {

    enum class State {
        Empty,
        Exists,
        Filling,
        Complete
    }

    var state: State by Delegates.observable(State.Empty) {
        property, oldValue, newValue ->
        fireListeners(newValue)
    }
    private set

    var cachedLength: Long = 0
        private set
    var totalLength: Long = UNKNOWN_LENGTH
        private set
    var hasError: Boolean = false
    var retries: Int = 0
    private var position: Long = 0
    private var appendStream: FileOutputStream? = null
    private var readStream: InputStream? = null
    private val listeners: HashSet<Listener> = HashSet()

    var transcode: String? = null

    val itemPath by lazy {
        File(cacheDir, path)
    }

    val itemTempPath by lazy {
        File(itemPath.absolutePath + TMP_FILE_SUFFIX)
    }

    var lastUsed: Long = 0
        private set

    val knownLength: Long
        get() {
            return if (totalLength>0) totalLength else cachedLength
        }

    val unused: Boolean
        get() = synchronized(this) {
            appendStream == null && readStream == null
        }


    init {
        if (changeListener!= null) {
            addListener(changeListener)
        }
        initilize()
    }

    private fun updateLastUsed() {
        lastUsed = System.currentTimeMillis()
    }

    private fun initilize() {
        if (itemPath.isFile) {
            state = State.Complete
            totalLength = itemPath.length()
            cachedLength = totalLength
            lastUsed = itemPath.lastModified()
        } else if (itemTempPath.isFile) {
            state = State.Exists
            cachedLength = itemTempPath.length()
            totalLength = UNKNOWN_LENGTH
            lastUsed = itemTempPath.lastModified()
        }

    }

    public fun destroy() {
        var dir: File? = null
        if (itemPath.isFile) {
            dir = itemPath.parentFile
            itemPath.delete()
        }
        else if (itemTempPath.isFile) {
            dir = itemTempPath.parentFile
            itemTempPath.delete()
        }

        state = State.Empty
        cachedLength = 0
        totalLength = UNKNOWN_LENGTH

        fun deleteEmptyDirs(dir: File) {
            if (dir == cacheDir) return
            if (dir.isDirectory && dir.list().size==0) dir.delete()
            deleteEmptyDirs(dir.parentFile)
        }

        if (dir!= null) deleteEmptyDirs(dir)
        listeners.clear()

    }

    fun addListener(l:Listener) = synchronized(this@CacheItem) {listeners.add(l)}
    fun removeListener(l:Listener) = synchronized(this@CacheItem) {listeners.remove(l)}

    private fun fireListeners(newValue: State) = synchronized(this) {
        for (l in listeners) {
            l.onItemChange(path, newValue)
        }
    }

    fun openForAppend(forceNew: Boolean = false) {
        if (state == State.Complete) {
            throw IllegalStateException("Cache entry is already complete")
        } else if (state == State.Filling) {
            throw IllegalStateException("Already opened for append")
        }

        synchronized(this) {
            if (state == State.Empty) {
                itemTempPath.parentFile.mkdirs()
                itemTempPath.createNewFile()
            }

            appendStream = FileOutputStream(itemTempPath, true)
            if (state == State.Exists && forceNew) {
                appendStream!!.channel.use {
                    it.truncate(0)
                }
                cachedLength = 0
                totalLength = UNKNOWN_LENGTH
                appendStream = FileOutputStream(itemTempPath)

            }
            state = State.Filling
        }
    }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (appendStream == null) {
            throw IllegalStateException("CacheItem is not opened for appending")
        }

        synchronized(this@CacheItem) {
            appendStream!!.write(buffer, offset, length)
            cachedLength += length
            (this as java.lang.Object).notifyAll()
        }


    }

    fun closeForAppend(isComplete: Boolean = false) {
        if (appendStream == null) {
            throw IllegalStateException("CacheItem is not opened for appending")
        }
        synchronized(this@CacheItem) {
            if (isComplete) {
                itemTempPath.renameTo(itemPath)
                state = State.Complete
                totalLength = cachedLength
            } else {
                state = State.Exists
            }
            appendStream!!.close()
            appendStream = null

            (this as java.lang.Object).notifyAll()
        }

    }

    fun injectFile(f:File) = synchronized(this){
        if (state != State.Empty && state != State.Exists) throw IllegalStateException("Can inject only to empty or partially downloaded")
        if (state == State.Exists) {
            itemTempPath.delete()
        }
        val sz = f.length()
        val res = f.renameTo(itemPath)
        if (!res ) {
            copyFile(f, itemPath)
            f.delete()
        }
        cachedLength = sz
        totalLength = sz
        state = State.Complete
    }

    fun openForRead(fromPosition:Long=0) {
        if (readStream != null) {
            throw IllegalStateException("Already opened for read")
        }
        position = fromPosition
        synchronized(this) {
            readStream = when (state) {
                State.Empty -> throw IllegalStateException("Cannot open empty CacheItem for read")
                State.Exists, State.Filling -> FileInputStream(itemTempPath)
                State.Complete -> FileInputStream(itemPath)
            }
        }
        if (position > 0) {
            val skipped = readStream!!.skip(position)
        }

    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (readStream == null) {
            throw IllegalStateException("Readstream is not opened")
        }
        val startState=state
        while (true) {
            val read = readStream!!.read(buffer, offset, length)
            if (read >= 0) {
                position += read
                return read

            } else {
                // this is tricky - if we are still filling cache we need wait until cache file has more data
                synchronized(this@CacheItem) {
                    if (state == State.Filling) {
                        if (position < cachedLength) {
                            // have more, but may need to flush
                            appendStream?.flush()
                            Thread.sleep(100) //give it some time to flush
                        } else {
                            // wait for write in other thread
                            (this@CacheItem as java.lang.Object).wait(10000)
                        }

//                        readStream!!.close()
//                        val f = if (state == State.Complete) itemPath else itemTempPath
//                        readStream = FileInputStream(f)
//                        readStream!!.skip(position)
//
//                    } else if (state == State.Complete && state != startState){
//                        // state has changed to Completed in meanwhile
//                        readStream!!.close()
//                        readStream = FileInputStream(itemPath)
//                        readStream!!.skip(position)

                    } else {
                        return read
                    }

                    }


                }
            }
        }

        fun closeForRead() {
            if (readStream != null) {
                readStream!!.close()
                readStream = null
            }
        }

        override fun close() {
            if (appendStream != null) closeForAppend()
            if (readStream != null) closeForRead()

        }

        interface Listener {
            fun onItemChange(path: String, state:State)
        }
    }