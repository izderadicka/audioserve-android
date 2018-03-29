package eu.zderadicka.audioserve.net


import java.io.*
import java.util.HashSet
import kotlin.properties.Delegates

const val UNKNOWN_LENGTH: Long = -1L
const val TMP_FILE_SUFFIX = ".\$TMP\$"

class CacheItem(val uriPath: String, val cacheDir: File, val pathPrefix: String? = null) : Closeable {

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
    var cachedLength: Long = 0
    var totalLength: Long = UNKNOWN_LENGTH
    private var position: Long = 0
    private var appendStream: OutputStream? = null
    private var readStream: InputStream? = null
    private val listeners: HashSet<Listener> = HashSet()

    val itemPath by lazy {
        var path = uriPath
        if (pathPrefix != null && path.startsWith(pathPrefix)) {
            path = path.substring(pathPrefix.length)
        }
        File(cacheDir, path)
    }

    val itemTempPath by lazy {
        File(itemPath.absolutePath + TMP_FILE_SUFFIX)
    }

    var lastUsed: Long = 0
        private set(v: Long) {
            field = v
        }


    init {
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

    fun addListener(l:Listener) = synchronized(this@CacheItem) {listeners.add(l)}
    fun removeListener(l:Listener) = synchronized(this@CacheItem) {listeners.remove(l)}

    private fun fireListeners(newValue: State) = synchronized(this) {
        for (l in listeners) {
            l.onChange(uriPath, newValue)
        }
    }

    fun openForAppend() {
        if (state == State.Complete) {
            throw IllegalStateException("Cache entry is already complete")
        } else if (state == State.Filling) {
            throw IllegalStateException("Already opened for append")
        }

        if (state == State.Empty) {
            itemTempPath.parentFile.mkdirs()
            itemTempPath.createNewFile()
        }
        appendStream = FileOutputStream(itemTempPath, true)
        state = State.Filling
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

    fun openForRead() {
        if (readStream != null) {
            throw IllegalStateException("Already opened for read")
        }
        position = 0
        readStream = when (state) {
            State.Empty -> throw IllegalStateException("Cannot open empty CacheItem for read")
            State.Exists, State.Filling -> FileInputStream(itemTempPath)
            State.Complete -> FileInputStream(itemPath)
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
            fun onChange(path: String, state:State)
        }
    }