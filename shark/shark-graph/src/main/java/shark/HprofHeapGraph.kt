package shark

import java.io.File
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofRecord.HeapDumpRecord.ObjectRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.HprofVersion.ANDROID
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.INT
import shark.internal.FieldValuesReader
import shark.internal.HprofInMemoryIndex
import shark.internal.IndexedObject
import shark.internal.IndexedObject.IndexedClass
import shark.internal.IndexedObject.IndexedInstance
import shark.internal.IndexedObject.IndexedObjectArray
import shark.internal.IndexedObject.IndexedPrimitiveArray
import shark.internal.LruCache

/**
 * A [HeapGraph] that reads from an Hprof file indexed by [HprofIndex].
 */
class HprofHeapGraph internal constructor(
  private val header: HprofHeader,
  private val reader: RandomAccessHprofReader,
  private val index: HprofInMemoryIndex
) : CloseableHeapGraph {

  override val identifierByteSize: Int get() = header.identifierByteSize

  override val context = GraphContext()

  override val objectCount: Int
    get() = classCount + instanceCount + objectArrayCount + primitiveArrayCount

  override val classCount: Int
    get() = index.classCount

  override val instanceCount: Int
    get() = index.instanceCount

  override val objectArrayCount: Int
    get() = index.objectArrayCount

  override val primitiveArrayCount: Int
    get() = index.primitiveArrayCount

  override val gcRoots: List<GcRoot>
    get() = index.gcRoots()

  override val objects: Sequence<HeapObject>
    get() {
      var objectIndex = 0
      return index.indexedObjectSequence()
        .map {
          wrapIndexedObject(objectIndex++, it.second, it.first)
        }
    }

  override val classes: Sequence<HeapClass>
    get() {
      var objectIndex = 0
      return index.indexedClassSequence()
        .map {
          val objectId = it.first
          val indexedObject = it.second
          HeapClass(this, indexedObject, objectId, objectIndex++)
        }
    }

  override val instances: Sequence<HeapInstance>
    get() {
      var objectIndex = classCount
      return index.indexedInstanceSequence()
        .map {
          val objectId = it.first
          val indexedObject = it.second
          HeapInstance(this, indexedObject, objectId, objectIndex++)
        }
    }

  override val objectArrays: Sequence<HeapObjectArray>
    get() {
      var objectIndex = classCount + instanceCount
      return index.indexedObjectArraySequence().map {
        val objectId = it.first
        val indexedObject = it.second
        HeapObjectArray(this, indexedObject, objectId, objectIndex++)
      }
    }

  override val primitiveArrays: Sequence<HeapPrimitiveArray>
    get() {
      var objectIndex = classCount + instanceCount + objectArrayCount
      return index.indexedPrimitiveArraySequence().map {
        val objectId = it.first
        val indexedObject = it.second
        HeapPrimitiveArray(this, indexedObject, objectId, objectIndex++)
      }
    }

  private val objectCache = LruCache<Long, ObjectRecord>(INTERNAL_LRU_CACHE_SIZE)

  // java.lang.Object is the most accessed class in Heap, so we want to memoize a reference to it
  private val javaLangObjectClass: HeapClass? = findClassByName("java.lang.Object")

  internal val objectArrayRecordNonElementSize = 2 * identifierByteSize + 2 * INT.byteSize

  internal val primitiveArrayRecordNonElementSize =
    identifierByteSize + 2 * INT.byteSize + BYTE.byteSize

  /**
   * This is only public so that we can publish stats. Accessing this requires casting
   * [HeapGraph] to [HprofHeapGraph] so it's really not a public API. May change at any time!
   */
  fun lruCacheStats(): String = objectCache.toString()

  override fun findObjectById(objectId: Long): HeapObject {
    return findObjectByIdOrNull(objectId) ?: throw IllegalArgumentException(
      "Object id $objectId not found in heap dump."
    )
  }

  override fun findObjectByIndex(objectIndex: Int): HeapObject {
    require(objectIndex in 0 until objectCount) {
      "$objectIndex should be in range [0, $objectCount["
    }
    val (objectId, indexedObject) = index.objectAtIndex(objectIndex)
    return wrapIndexedObject(objectIndex, indexedObject, objectId)
  }

  override fun findObjectByIdOrNull(objectId: Long): HeapObject? {
    if (objectId == javaLangObjectClass?.objectId) return javaLangObjectClass

    val (objectIndex, indexedObject) = index.indexedObjectOrNull(objectId) ?: return null
    return wrapIndexedObject(objectIndex, indexedObject, objectId)
  }

  override fun findClassByName(className: String): HeapClass? {
    val heapDumpClassName = if (header.version != ANDROID) {
      val indexOfArrayChar = className.indexOf('[')
      if (indexOfArrayChar != -1) {
        val dimensions = (className.length - indexOfArrayChar) / 2
        val componentClassName = className.substring(0, indexOfArrayChar)
        "[".repeat(dimensions) + when (componentClassName) {
          "char" -> 'C'
          "float" -> 'F'
          "double" -> 'D'
          "byte" -> 'B'
          "short" -> 'S'
          "int" -> 'I'
          "long" -> 'J'
          else -> "L$componentClassName;"
        }
      } else {
        className
      }
    } else {
      className
    }
    val classId = index.classId(heapDumpClassName)
    return if (classId == null) {
      null
    } else {
      return findObjectById(classId) as HeapClass
    }
  }

  override fun objectExists(objectId: Long): Boolean {
    return index.objectIdIsIndexed(objectId)
  }

  override fun findHeapDumpIndex(objectId: Long): Int {
    val (_, indexedObject) = index.indexedObjectOrNull(objectId)?: throw IllegalArgumentException(
      "Object id $objectId not found in heap dump."
    )
    val position = indexedObject.position

    var countObjectsBefore = 1
    index.indexedObjectSequence()
      .forEach {
        if (position > it.second.position) {
          countObjectsBefore++
        }
      }
    return countObjectsBefore
  }

  override fun findObjectByHeapDumpIndex(heapDumpIndex: Int): HeapObject {
    require(heapDumpIndex in 1..objectCount) {
      "$heapDumpIndex should be in range [1, $objectCount]"
    }
    val (objectId, _) = index.indexedObjectSequence().toList().sortedBy { it.second.position }[heapDumpIndex]
    return findObjectById(objectId)
  }

  override fun close() {
    reader.close()
  }

  internal fun classDumpStaticFields(indexedClass: IndexedClass): List<StaticFieldRecord> {
    return index.classFieldsReader.classDumpStaticFields(indexedClass)
  }

  internal fun classDumpFields(indexedClass: IndexedClass): List<FieldRecord> {
    return index.classFieldsReader.classDumpFields(indexedClass)
  }

  internal fun classDumpHasReferenceFields(indexedClass: IndexedClass): Boolean {
    return index.classFieldsReader.classDumpHasReferenceFields(indexedClass)
  }

  internal fun fieldName(
    classId: Long,
    fieldRecord: FieldRecord
  ): String {
    return index.fieldName(classId, fieldRecord.nameStringId)
  }

  internal fun staticFieldName(
    classId: Long,
    fieldRecord: StaticFieldRecord
  ): String {
    return index.fieldName(classId, fieldRecord.nameStringId)
  }

  internal fun createFieldValuesReader(record: InstanceDumpRecord) =
    FieldValuesReader(record, identifierByteSize)

  internal fun className(classId: Long): String {
    val hprofClassName = index.className(classId)
    if (header.version != ANDROID) {
      if (hprofClassName.startsWith('[')) {
        val arrayCharLastIndex = hprofClassName.lastIndexOf('[')
        val brackets = "[]".repeat(arrayCharLastIndex + 1)
        return when (val typeChar = hprofClassName[arrayCharLastIndex + 1]) {
          'L' -> {
            val classNameStart = arrayCharLastIndex + 2
            hprofClassName.substring(classNameStart, hprofClassName.length - 1) + brackets
          }
          'Z' -> "boolean$brackets"
          'C' -> "char$brackets"
          'F' -> "float$brackets"
          'D' -> "double$brackets"
          'B' -> "byte$brackets"
          'S' -> "short$brackets"
          'I' -> "int$brackets"
          'J' -> "long$brackets"
          else -> error("Unexpected type char $typeChar")
        }
      }
    }
    return hprofClassName
  }

  internal fun readObjectArrayDumpRecord(
    objectId: Long,
    indexedObject: IndexedObjectArray
  ): ObjectArrayDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      readObjectArrayDumpRecord()
    }
  }

  internal fun readObjectArrayByteSize(
    objectId: Long,
    indexedObject: IndexedObjectArray
  ): Int {
    val cachedRecord = objectCache[objectId] as ObjectArrayDumpRecord?
    if (cachedRecord != null) {
      return cachedRecord.elementIds.size * identifierByteSize
    }
    val position = indexedObject.position + identifierByteSize + PrimitiveType.INT.byteSize
    val size = PrimitiveType.INT.byteSize.toLong()
    val thinRecordSize = reader.readRecord(position, size) {
      readInt()
    }
    return thinRecordSize * identifierByteSize
  }

  internal fun readPrimitiveArrayDumpRecord(
    objectId: Long,
    indexedObject: IndexedPrimitiveArray
  ): PrimitiveArrayDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      readPrimitiveArrayDumpRecord()
    }
  }

  internal fun readPrimitiveArrayByteSize(
    objectId: Long,
    indexedObject: IndexedPrimitiveArray
  ): Int {
    val cachedRecord = objectCache[objectId] as PrimitiveArrayDumpRecord?
    if (cachedRecord != null) {
      return when (cachedRecord) {
        is BooleanArrayDump -> cachedRecord.array.size * PrimitiveType.BOOLEAN.byteSize
        is CharArrayDump -> cachedRecord.array.size * PrimitiveType.CHAR.byteSize
        is FloatArrayDump -> cachedRecord.array.size * PrimitiveType.FLOAT.byteSize
        is DoubleArrayDump -> cachedRecord.array.size * PrimitiveType.DOUBLE.byteSize
        is ByteArrayDump -> cachedRecord.array.size * PrimitiveType.BYTE.byteSize
        is ShortArrayDump -> cachedRecord.array.size * PrimitiveType.SHORT.byteSize
        is IntArrayDump -> cachedRecord.array.size * PrimitiveType.INT.byteSize
        is LongArrayDump -> cachedRecord.array.size * PrimitiveType.LONG.byteSize
      }
    }
    val position = indexedObject.position + identifierByteSize + PrimitiveType.INT.byteSize
    val size = reader.readRecord(position, PrimitiveType.INT.byteSize.toLong()) {
      readInt()
    }
    return size * indexedObject.primitiveType.byteSize
  }

  internal fun readClassDumpRecord(
    objectId: Long,
    indexedObject: IndexedClass
  ): ClassDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      readClassDumpRecord()
    }
  }

  internal fun readInstanceDumpRecord(
    objectId: Long,
    indexedObject: IndexedInstance
  ): InstanceDumpRecord {
    return readObjectRecord(objectId, indexedObject) {
      readInstanceDumpRecord()
    }
  }

  private fun <T : ObjectRecord> readObjectRecord(
    objectId: Long,
    indexedObject: IndexedObject,
    readBlock: HprofRecordReader.() -> T
  ): T {
    val objectRecordOrNull = objectCache[objectId]
    @Suppress("UNCHECKED_CAST")
    if (objectRecordOrNull != null) {
      return objectRecordOrNull as T
    }
    return reader.readRecord(indexedObject.position, indexedObject.recordSize) {
      readBlock()
    }.apply { objectCache.put(objectId, this) }
  }

  private fun wrapIndexedObject(
    objectIndex: Int,
    indexedObject: IndexedObject,
    objectId: Long
  ): HeapObject {
    return when (indexedObject) {
      is IndexedClass -> {
        HeapClass(this, indexedObject, objectId, objectIndex)
      }
      is IndexedInstance -> {
        HeapInstance(this, indexedObject, objectId, objectIndex)
      }
      is IndexedObjectArray -> {
        HeapObjectArray(this, indexedObject, objectId, objectIndex)
      }
      is IndexedPrimitiveArray -> HeapPrimitiveArray(this, indexedObject, objectId, objectIndex)
    }
  }

  companion object {

    /**
     * This is not a public API, it's only public so that we can evaluate the effectiveness of
     * different cache size in tests in a different module.
     *
     * LRU cache size of 3000 is a sweet spot to balance hits vs memory usage.
     * This is based on running an instrumented test a bunch of time on a
     * Pixel 2 XL API 28. Hit count was ~120K, miss count ~290K
     */
    var INTERNAL_LRU_CACHE_SIZE = 3000

    /**
     * A facility for opening a [CloseableHeapGraph] from a [File].
     * This first parses the file headers with [HprofHeader.parseHeaderOf], then indexes the file content
     * with [HprofIndex.indexRecordsOf] and then opens a [CloseableHeapGraph] from the index, which
     * you are responsible for closing after using.
     */
    fun File.openHeapGraph(
      proguardMapping: ProguardMapping? = null,
      indexedGcRootTypes: Set<HprofRecordTag> = HprofIndex.defaultIndexedGcRootTags()
    ): CloseableHeapGraph {
      return FileSourceProvider(this).openHeapGraph(proguardMapping, indexedGcRootTypes)
    }

    fun DualSourceProvider.openHeapGraph(
      proguardMapping: ProguardMapping? = null,
      indexedGcRootTypes: Set<HprofRecordTag> = HprofIndex.defaultIndexedGcRootTags()
    ): CloseableHeapGraph {
      // TODO We can probably remove the concept of DualSourceProvider. Opening a heap graph requires
      //  a random access reader which is built from a random access source + headers.
      //  Also require headers, and the index.
      //  So really we're:
      //  1) Reader the headers from an okio source
      //  2) Reading the whole source streaming to create the index. Wondering if we really need to parse
      //  the headers, close the file then parse / skip the header part. Can't the parsing + indexing give
      //  us headers + index?
      //  3) Using the index + headers + a random access source on the content to create a closeable
      //  abstraction.
      //  Note: should see if Okio has a better abstraction for random access now.
      //  Also Use FileSystem + Path instead of File as the core way to open a file based heap dump.
      val header = openStreamingSource().use { HprofHeader.parseHeaderOf(it) }
      val index = HprofIndex.indexRecordsOf(this, header, proguardMapping, indexedGcRootTypes)
      return index.openHeapGraph()
    }
  }
}
