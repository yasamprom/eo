/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016-2022 Yegor Bugayenko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.eolang.maven;

import com.jcabi.log.Logger;
import com.yegor256.tojos.Tojo;
import com.yegor256.tojos.Tojos;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.cactoos.Input;
import org.cactoos.scalar.Sticky;
import org.cactoos.scalar.Unchecked;

/**
 * Pull EO XML files from Objectionary and parse them into XML.
 *
 * @since 0.1
 */
@Mojo(
    name = "pull",
    defaultPhase = LifecyclePhase.PROCESS_SOURCES,
    threadSafe = true
)
public final class PullMojo extends SafeMojo {

    /**
     * The directory where to process to.
     */
    public static final String DIR = "04-pull";

    /**
     * The Git hash to pull objects from, in objectionary.
     * @since 0.21.0
     */
    @SuppressWarnings("PMD.ImmutableField")
    @Parameter(property = "eo.hash", required = true, defaultValue = "master")
    private String hash = "master";

    /**
     * Pull again even if the .eo file is already present?
     * @checkstyle MemberNameCheck (7 lines)
     * @since 0.10.0
     */
    @Parameter(property = "eo.overWrite", required = true, defaultValue = "false")
    private boolean overWrite;

    /**
     * Target directory.
     * @checkstyle MemberNameCheck (7 lines)
     */
    @Parameter(property = "eo.home")
    @SuppressWarnings("PMD.ImmutableField")
    private Path outputPath = Paths.get(System.getProperty("user.home")).resolve(".eo");

    /**
     * The objectionary.
     */
    @SuppressWarnings("PMD.ImmutableField")
    private Objectionary objectionary;

    @Override
    public void exec() throws IOException {
        final Collection<Tojo> tojos = this.scopedTojos().select(
            row -> !row.exists(AssembleMojo.ATTR_EO)
                && !row.exists(AssembleMojo.ATTR_XMIR)
        );
        if (this.objectionary == null) {
            final String full = new HashOfTag(this.hash).hash();
            final String small = full.substring(0, 7);
            this.objectionary = new PullMojo.FallbackSwapOy(
                new OyHome(
                    small,
                    this.outputPath
                ),
                new OyCaching(
                    small,
                    this.outputPath,
                    new OyRemote(full)
                ),
                this.forceUpdate()
            );
        }
        if (!tojos.isEmpty()) {
            for (final Tojo tojo : tojos) {
                tojo.set(
                    AssembleMojo.ATTR_EO,
                    this.pull(tojo.get(Tojos.KEY)).toAbsolutePath().toString()
                );
            }
            Logger.info(
                this, "%d program(s) pulled from %s",
                tojos.size(), this.objectionary
            );
        }
    }

    /**
     * Is force update option enabled.
     * @return True if option enabled and false otherwise
     */
    private boolean forceUpdate() {
        return this.session.getRequest().isUpdateSnapshots();
    }

    /**
     * Pull one object.
     *
     * @param name Name of the object, e.g. "org.eolang.io.stdout"
     * @return The path of .eo file
     * @throws IOException If fails
     */
    private Path pull(final String name) throws IOException {
        final Path src = new Place(name).make(
            this.targetDir.toPath().resolve(PullMojo.DIR), "eo"
        );
        if (src.toFile().exists() && !this.overWrite) {
            Logger.debug(
                this, "The object '%s' already pulled to %s (and 'overWrite' is false)",
                name, Save.rel(src)
            );
        } else {
            new Save(
                this.objectionary.get(name),
                src
            ).save();
            Logger.debug(
                this, "The sources of the object '%s' pulled to %s",
                name, Save.rel(src)
            );
        }
        return src;
    }

    /**
     * Fallback which can swap primary/secondary repos.
     * <p/>
     * The key purpose of this class is to allow dynamic determination
     * of which Oy (fist or second) to use as primary and which as fallback based on given
     * boolean property.
     * <p/>
     * For {@link PullMojo} this is used to bypass reading from cache by always checking remote
     * first and only fallback to local in case of object miss:
     * <pre>
     *     new PullMojo.FallbackSwapOy(
     *         &lt local &gt,
     *         &lt remote &gt,
     *         this.forceUpdate()
     *     );
     * </pre>
     *
     * @since 1.0
     */
    public static final class FallbackSwapOy implements Objectionary {
        /**
         * Swapped Oy.
         */
        private final Unchecked<Objectionary> swapped;

        /**
         * Ctor.
         *
         * @param first Initial primary
         * @param second Initial secondary
         * @param swap Whether to swap
         */
        public FallbackSwapOy(
            final Objectionary first,
            final Objectionary second,
            final boolean swap
        ) {
            this.swapped = new Unchecked<>(
                new Sticky<>(
                    () -> {
                        final Objectionary result;
                        if (swap) {
                            result = new OyFallback(
                                second,
                                first
                            );
                        } else {
                            result = new OyFallback(
                                first,
                                second
                            );
                        }
                        return result;
                    }
                )
            );
        }

        @Override
        public Input get(final String name) throws IOException {
            return this.swapped.value().get(name);
        }

        @Override
        public String toString() {
            return this.swapped.value().toString();
        }
    }
}
