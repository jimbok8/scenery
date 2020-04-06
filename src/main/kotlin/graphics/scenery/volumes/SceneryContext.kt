package graphics.scenery.volumes

import graphics.scenery.textures.Texture
import graphics.scenery.textures.Texture.BorderColor
import graphics.scenery.textures.UpdatableTexture.TextureExtents
import graphics.scenery.textures.Texture.RepeatMode
import graphics.scenery.textures.UpdatableTexture.TextureUpdate
import graphics.scenery.backends.ShaderType
import graphics.scenery.textures.UpdatableTexture
import graphics.scenery.utils.LazyLogger
import net.imglib2.type.numeric.NumericType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.numeric.real.FloatType
import org.joml.*
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.xxhash.XXHash
import tpietzsch.backend.*
import tpietzsch.cache.TextureCache
import tpietzsch.example2.LookupTextureARGB
import tpietzsch.shadergen.Shader
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import tpietzsch.backend.Texture as BVVTexture

/**
 * Context class for interaction with BigDataViewer-generated shaders.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @author Tobias Pietzsch <pietzsch@mpi-cbg.de>
 */
open class SceneryContext(val node: VolumeManager) : GpuContext {
    private val logger by LazyLogger()

    data class BindingState(var binding: Int, var uniformName: String?, var reallocate: Boolean = false)

    private val pboBackingStore = HashMap<StagingBuffer, ByteBuffer>()

    /** Factory for the autogenerated shaders. */
    val factory = VolumeShaderFactory()
    /** Reference to the currently bound texture cache. */
    protected var currentlyBound: Texture? = null
    /** Hashmap for references to the currently bound LUTs/texture atlases. */
    protected var currentlyBoundLuts = ConcurrentHashMap<String, Texture>()
    /** Hashmap for storing associations between [Texture] objects, texture slots and uniform names. */
    protected var bindings = ConcurrentHashMap<BVVTexture, BindingState>()

    /** Storage for deferred bindings, where the association between uniform and texture unit is not known upfront. */
    protected var deferredBindings = ConcurrentHashMap<BVVTexture, (String) -> Unit>()

    protected var samplerKeys = listOf("volumeCache", "lutSampler", "volume_", "transferFunction_", "colorMap_")

    val uniformSetter = SceneryUniformSetter()
    /**
     * Uniform setter class
     */
    inner class SceneryUniformSetter: SetUniforms {
        private var modified: Boolean = false
        override fun shouldSet(modified: Boolean): Boolean = modified

        /**
         * Sets the uniform with [name] to the Integer [v0].
         */
        override fun setUniform1i(name: String, v0: Int) {
            logger.debug("Setting uniform $name to $v0")
            if(samplerKeys.any { name.startsWith(it) }) {
                val binding = bindings.entries.find { it.value.binding == v0 }
                if(binding != null) {
                    bindings[binding.key] = BindingState(v0, name)
                } else {
                    logger.warn("Binding for $name slot $v0 not found.")
                }
            } else {
                node.shaderProperties[name] = v0
            }
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Integer 2-vector [v0] and [v1].
         */
        override fun setUniform2i(name: String, v0: Int, v1: Int) {
            node.shaderProperties[name] = Vector2i(v0, v1)
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Integer 3-vector [v0],[v1],[v2].
         */
        override fun setUniform3i(name: String, v0: Int, v1: Int, v2: Int) {
            node.shaderProperties[name] = Vector3i(v0, v1, v2)
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Integer 4-vector [v0],[v1],[v2],[v3].
         */
        override fun setUniform4i(name: String, v0: Int, v1: Int, v2: Int, v3: Int) {
            node.shaderProperties[name] = Vector4i(v0, v1, v2, v3)
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Float [v0].
         */
        override fun setUniform1f(name: String, v0: Float) {
            node.shaderProperties[name] = v0
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Float 2-vector [v0],[v1].
         */
        override fun setUniform2f(name: String, v0: Float, v1: Float) {
            node.shaderProperties[name] = Vector2f(v0, v1)
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Float 3-vector [v0],[v1],[v2].
         */
        override fun setUniform3f(name: String, v0: Float, v1: Float, v2: Float) {
            node.shaderProperties[name] = Vector3f(v0, v1, v2)
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Float 4-vector [v0],[v1],[v2],[v3].
         */
        override fun setUniform4f(name: String, v0: Float, v1: Float, v2: Float, v3: Float) {
            node.shaderProperties[name] = Vector4f(v0, v1, v2, v3)
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Float array given by [value], containing [count] single values.
         */
        override fun setUniform1fv(name: String, count: Int, value: FloatArray) {
            node.shaderProperties[name] = value
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Float array given by [value], containing [count] 2-vectors.
         */
        override fun setUniform2fv(name: String, count: Int, value: FloatArray) {
            node.shaderProperties[name] = value
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Float array given by [value], containing [count] 3-vectors.
         * To conform with OpenGL/Vulkan UBO alignment rules, the array given will be padded to a 4-vector by zeroes.
         */
        override fun setUniform3fv(name: String, count: Int, value: FloatArray) {
            // in UBOs, arrays of vectors need to be padded, such that they start on
            // word boundaries, e.g. a 3-vector needs to start on byte 16.
            val padded = ArrayList<Float>(4*count)
            value.asSequence().windowed(3, 3).forEach {
                padded.addAll(it)
                padded.add(0.0f)
            }

            node.shaderProperties[name] = padded.toFloatArray()
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Float 3x3 matrix given in [value]. Set [transpose] if the matrix should
         * be transposed prior to setting.
         */
        override fun setUniformMatrix3f(name: String, transpose: Boolean, value: FloatBuffer) {
            val matrix = value.duplicate()
            if(matrix.position() == matrix.capacity()) {
                matrix.flip()
            }

            val m = Matrix4f(matrix)
            if(transpose) {
                m.transpose()
            }

            node.shaderProperties[name] = m
            modified = true
        }

        /**
         * Sets the uniform with [name] to the Float 4x4 matrix given in [value]. Set [transpose] if the matrix should
         * be transposed prior to setting.
         */
        override fun setUniformMatrix4f(name: String, transpose: Boolean, value: FloatBuffer) {
            val matrix = value.duplicate()
            if(matrix.position() == matrix.capacity()) {
                matrix.flip()
            }

            val m = Matrix4f(matrix)
            if(transpose) {
                m.transpose()
            }

            node.shaderProperties[name] = m
            modified = true
        }
    }

    var currentShader: Shader? = null
    /**
     * Update the shader set with the new [shader] given.
     */
    override fun use(shader: Shader) {
        if(currentShader == null || currentShader != shader) {
            factory.updateShaders(
                hashMapOf(
                    ShaderType.VertexShader to shader,
                    ShaderType.FragmentShader to shader))
            currentShader = shader
        }
    }

    /**
     * Returns the uniform setter for [shader].
     */
    override fun getUniformSetter(shader: Shader): SetUniforms {
        return uniformSetter
    }

    /**
     * @param pbo StagingBuffer to bind
     * @return id of previously bound pbo
     */
    override fun bindStagingBuffer(pbo: StagingBuffer): Int {
        logger.debug("Binding PBO $pbo")
        return 0
    }

    /**
     * @param id pbo id to bind
     * @return id of previously bound pbo
     */
    override fun bindStagingBufferId(id: Int): Int {
        logger.debug("Binding PBO $id")
        return id
    }

    /**
     * @param texture texture to bind
     * @return id of previously bound texture
     */
    override fun bindTexture(texture: BVVTexture): Int {
        logger.debug("Binding $texture and updating GT")
        val (channels, type: NumericType<*>, normalized) = when(texture.texInternalFormat()) {
            BVVTexture.InternalFormat.R8 -> Triple(1, UnsignedByteType(), true)
            BVVTexture.InternalFormat.R16 -> Triple(1, UnsignedShortType(), true)
            BVVTexture.InternalFormat.RGBA8 -> Triple(4, UnsignedByteType(), true)
            BVVTexture.InternalFormat.RGBA8UI -> Triple(4, UnsignedByteType(), false)
            BVVTexture.InternalFormat.FLOAT32 -> Triple(1, FloatType(), false)
            BVVTexture.InternalFormat.UNKNOWN -> TODO()
            else -> throw UnsupportedOperationException("Unknown internal format ${texture.texInternalFormat()}")
        } as Triple<Int, NumericType<*>, Boolean>

        val repeat = when(texture.texWrap()) {
            BVVTexture.Wrap.CLAMP_TO_BORDER_ZERO -> RepeatMode.ClampToBorder
            BVVTexture.Wrap.CLAMP_TO_EDGE -> RepeatMode.ClampToEdge
            BVVTexture.Wrap.REPEAT -> RepeatMode.Repeat
            else -> throw UnsupportedOperationException("Unknown wrapping mode: ${texture.texWrap()}")
        }

        if (texture is TextureCache) {
            if(currentlyBound != null && node.material.textures.get("volumeCache") == currentlyBound) {
                return 0
            }

            logger.debug("Binding and updating cache $texture")
            val gt = UpdatableTexture(
                Vector3i(texture.texWidth(), texture.texHeight(), texture.texDepth()),
                channels,
                type,
                null,
                repeat.all(),
                BorderColor.TransparentBlack,
                normalized,
                false,
                minFilter = Texture.FilteringMode.Linear,
                maxFilter = Texture.FilteringMode.Linear)

            node.material.textures.put("volumeCache", gt)

            currentlyBound = gt

            node.material.needsTextureReload = true
        } else {
            val lutName = bindings[texture]?.uniformName
            logger.debug("lutName is $lutName for $texture")

            val db = { lut: String ->
                if (node.material.textures.get(lut) != null
                    && currentlyBoundLuts.get(lut) != null
                    && node.material.textures.get(lut) == currentlyBoundLuts[lut]) {
                } else {
                    val contents = when(texture) {
                        is LookupTextureARGB -> null
                        is VolumeManager.SimpleTexture2D -> texture.data
                        else -> null
                    }

                    val filterLinear = when(texture) {
                        is LookupTextureARGB -> Texture.FilteringMode.NearestNeighbour
                        else -> Texture.FilteringMode.Linear
                    }

                    val gt = UpdatableTexture(
                        Vector3i(texture.texWidth(), texture.texHeight(), texture.texDepth()),
                        channels,
                        type,
                        contents,
                        repeat.all(),
                        BorderColor.TransparentBlack,
                        normalized,
                        false,
                        minFilter = filterLinear,
                        maxFilter = filterLinear)

                    node.material.textures.put(lut, gt)

                    currentlyBoundLuts[lut] = gt

                    node.material.needsTextureReload = true
                }
            }

            logger.debug("Adding deferred binding for $texture/$lutName")

            if(lutName == null) {
                deferredBindings.put(texture, db)
                return -1
            } else {
                db.invoke(lutName)
            }
        }
        return 0
    }

    /**
     * Runs all bindings that have been deferred to a later point. Necessary for some textures
     * to be compatible with the OpenGL binding model.
     */
    fun runDeferredBindings() {
        val removals = ArrayList<BVVTexture>(deferredBindings.size)

        logger.debug("Running deferred bindings, got ${deferredBindings.size}")
        deferredBindings.forEach { texture, func ->
            val binding = bindings[texture]
            val samplerName = binding?.uniformName
            if(binding != null && samplerName != null) {
                func.invoke(samplerName)
                removals.add(texture)
            } else {
                if(node.readyToRender()) {
                    logger.error("Binding for $texture not found, despite trying deferred binding. (binding=$binding/sampler=$samplerName)")
                }
            }
        }

        removals.forEach { deferredBindings.remove(it) }
    }

    fun clearBindings() {
        val caches = bindings.filter { it is TextureCache }
        caches.map { bindings.remove(it.key) }
        currentlyBound = null
    }

    /**
     * @param texture texture to bind
     * @param unit texture unit to bind to
     */
    override fun bindTexture(texture: BVVTexture?, unit: Int) {
        logger.debug("Binding $texture to $unit")
        if(texture != null) {
            val binding = bindings[texture]
            if(binding != null) {
                bindings[texture] = BindingState(unit, binding.uniformName)
            } else {
                val previousName = bindings.filter { it.value.binding == unit }.entries.firstOrNull()?.value?.uniformName
                bindings[texture] = BindingState(unit, previousName)
            }
        }
    }

    /**
     * @param id texture id to bind
     * @param numTexDimensions texture target: 1, 2, or 3
     * @return id of previously bound texture
     */
    override fun bindTextureId(id: Int, numTexDimensions: Int): Int {
        return 0
    }

    /**
     * Maps a given [pbo] to a native memory-backed [ByteBuffer] and returns it.
     * The allocated buffers will be cached.
     */
    override fun map(pbo: StagingBuffer): Buffer {
        logger.debug("Mapping $pbo... (${pboBackingStore.size} total)")
        return pboBackingStore.computeIfAbsent(pbo) {
            MemoryUtil.memAlloc(pbo.sizeInBytes)
        }
    }

    /**
     * Unmaps a buffer given by [pbo]. This function currently has no effect.
     */
    override fun unmap(pbo: StagingBuffer) {
        logger.debug("Unmapping $pbo...")
    }

    /**
     * Marks a given [texture] for reallocation.
     */
    override fun delete(texture: BVVTexture) {
        logger.debug("Marking $texture for reallocation")
        bindings[texture]?.reallocate = true
    }

    /**
     * Updates the memory allocated to [texture] with the contents of the staging buffer [pbo].
     * This function updates only the part of the texture at the offsets [xoffset], [yoffset], [zoffset], with the given
     * [width], [height], and [depth]. In case the texture data does not start at offset 0, [pixels_buffer_offset] can be
     * set in addition.
     */
    override fun texSubImage3D(pbo: StagingBuffer, texture: Texture3D, xoffset: Int, yoffset: Int, zoffset: Int, width: Int, height: Int, depth: Int, pixels_buffer_offset: Long) {
        logger.debug("Updating 3D texture via PBO from $texture: dx=$xoffset dy=$yoffset dz=$zoffset w=$width h=$height d=$depth offset=$pixels_buffer_offset")
        val tex = bindings.entries.find { it.key == texture }
        if(tex == null) {
            if(node.readyToRender()) {
                logger.warn("No binding found for $texture (PBO)")
            }
            return
        }
        val texname = tex.value.uniformName

        if(texname == null) {
            logger.warn("Binding not initialiased for $texture")
            return
        }

        val tmpStorage = (map(pbo) as ByteBuffer).duplicate().order(ByteOrder.LITTLE_ENDIAN)
        tmpStorage.position(pixels_buffer_offset.toInt())

        val tmp = MemoryUtil.memAlloc(width*height*depth*2)
        tmpStorage.limit(tmpStorage.position() + width*height*depth*2)
        tmp.put(tmpStorage)
        tmp.flip()

        val gt = node.material.textures[texname] as? UpdatableTexture ?: throw IllegalStateException("Texture for $texname is null or not updatable")

        if(tex.value.reallocate) {
            gt.clearUpdates()
            gt.dimensions = Vector3i(width, height, depth)
            gt.contents = null
            gt.normalized = false

            val update = TextureUpdate(
                TextureExtents(xoffset, yoffset, zoffset, width, height, depth),
                tmp, deallocate = true)
            gt.updates.add(update)
        } else {
            val update = TextureUpdate(
                TextureExtents(xoffset, yoffset, zoffset, width, height, depth),
                tmp, deallocate = true)

            gt.updates.add(update)
        }

        node.material.textures[texname] = gt
        node.material.needsTextureReload = true
    }

    data class UpdateParameters(val xoffset: Int, val yoffset: Int, val zoffset: Int, val width: Int, val height: Int, val depth: Int, val hash: Int)
    val lastUpdates = ConcurrentHashMap<Texture3D, UpdateParameters>()

    /**
     * Updates the memory allocated to [texture] with the contents of [pixels].
     * This function updates only the part of the texture at the offsets [xoffset], [yoffset], [zoffset], with the given
     * [width], [height], and [depth].
     */
    override fun texSubImage3D(texture: Texture3D, xoffset: Int, yoffset: Int, zoffset: Int, width: Int, height: Int, depth: Int, pixels: Buffer) {
        val params = UpdateParameters(xoffset, yoffset, zoffset, width, height, depth, XXHash.XXH32(pixels as ByteBuffer, 42))
        if(lastUpdates[texture] == params) {
            logger.debug("Updates already seen, skipping")
            return
        }

        logger.debug("Updating 3D texture via Texture3D from $texture: dx=$xoffset dy=$yoffset dz=$zoffset w=$width h=$height d=$depth")

        val tex = bindings.entries.find { it.key == texture }
        if(tex == null) {
            if(node.readyToRender()) {
                logger.warn("No binding found for $texture/${texture.hashCode()} (Texture3D). Bindings exist for ${bindings.keys.joinToString { it.toString() }}.")
            }
            return
        }
        val texname = tex.value.uniformName

        if(texname == null) {
            logger.warn("Binding not initialised for $texture.")
            return
        }

        if(pixels is ByteBuffer) {
            val p = pixels.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val allocationSize = width * height * depth * when(texture.texInternalFormat()) {
                BVVTexture.InternalFormat.R8 -> 1
                BVVTexture.InternalFormat.R16 -> 2
                BVVTexture.InternalFormat.RGBA8 -> 4
                BVVTexture.InternalFormat.RGBA8UI -> 4
                BVVTexture.InternalFormat.FLOAT32 -> 4
                BVVTexture.InternalFormat.UNKNOWN -> {
                    logger.error("Don't know how to determine texture size of $texture, assuming 1 byte, 1 channel.")
                    1
                }
            }
            val tmp = MemoryUtil.memAlloc(allocationSize)
            p.limit(p.position() + allocationSize)
            MemoryUtil.memCopy(p, tmp)
//            tmp.put(p)
//            tmp.flip()

            // TODO: add support for different data types
            val gt = node.material.textures[texname] as? UpdatableTexture ?: throw IllegalStateException("Texture for $texname is null or not updateable")

//            logger.info("for $texture: Texname=$texname, gt=$gt")
            if(tex.value.reallocate) {
                gt.clearUpdates()
                gt.dimensions = Vector3i(width, height, depth)
                gt.contents = null
                if(texture is LookupTextureARGB) {
                    gt.normalized = false
                }

                val update = TextureUpdate(
                    TextureExtents(xoffset, yoffset, zoffset, width, height, depth),
                    tmp, deallocate = true)
                gt.updates.add(update)
            } else {
                val update = TextureUpdate(
                    TextureExtents(xoffset, yoffset, zoffset, width, height, depth),
                    tmp, deallocate = true)

                gt.updates.add(update)
            }

            node.material.textures[texname] = gt
            node.material.needsTextureReload = true
        }

        lastUpdates[texture] = params
    }
}
