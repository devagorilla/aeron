/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;

import static io.aeron.logbuffer.FrameDescriptor.*;

/**
 * A {@link ControlledFragmentHandler} that sits in a chain-of-responsibility pattern that reassembles fragmented
 * messages so that the next handler in the chain only sees whole messages. This is for a single session on an
 * {@link Image} and not for multiple session {@link Image}s in a {@link Subscription}.
 *
 * Unfragmented messages are delegated without copy. Fragmented messages are copied to a temporary
 * buffer for reassembly before delegation.
 *
 * The {@link Header} passed to the delegate on assembling a message will be that of the last fragment.
 *
 * @see Image#controlledPoll(ControlledFragmentHandler, int)
 * @see Image#controlledPeek(long, ControlledFragmentHandler, long)
 */
public class ImageControlledFragmentAssembler implements ControlledFragmentHandler
{
    private final ControlledFragmentHandler delegate;
    private final BufferBuilder builder;

    /**
     * Construct an adapter to reassemble message fragments and delegate on whole messages.
     *
     * @param delegate onto which whole messages are forwarded.
     */
    public ImageControlledFragmentAssembler(final ControlledFragmentHandler delegate)
    {
        this(delegate, BufferBuilder.INITIAL_CAPACITY);
    }

    /**
     * Construct an adapter to reassemble message fragments and delegate on whole messages.
     *
     * @param delegate            onto which whole messages are forwarded.
     * @param initialBufferLength to be used for each session.
     */
    public ImageControlledFragmentAssembler(final ControlledFragmentHandler delegate, final int initialBufferLength)
    {
        this.delegate = delegate;
        this.builder = new BufferBuilder(initialBufferLength);
    }

    /**
     * Get the delegate unto which assembled messages are delegated.
     *
     * @return  the delegate unto which assembled messages are delegated.
     */
    public ControlledFragmentHandler delegate()
    {
        return delegate;
    }

    /**
     * Get the {@link BufferBuilder} for resetting this assembler.
     *
     * @return the {@link BufferBuilder} for resetting this assembler.
     */
    BufferBuilder bufferBuilder()
    {
        return builder;
    }

    /**
     * The implementation of {@link ControlledFragmentHandler} that reassembles and forwards whole messages.
     *
     * @param buffer containing the data.
     * @param offset at which the data begins.
     * @param length of the data in bytes.
     * @param header representing the meta data for the data.
     */
    public Action onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        final byte flags = header.flags();

        Action action = Action.CONTINUE;

        if ((flags & UNFRAGMENTED) == UNFRAGMENTED)
        {
            action = delegate.onFragment(buffer, offset, length, header);
        }
        else
        {
            if ((flags & BEGIN_FRAG_FLAG) == BEGIN_FRAG_FLAG)
            {
                builder.reset().append(buffer, offset, length);
            }
            else
            {
                final int limit = builder.limit();
                builder.append(buffer, offset, length);

                if ((flags & END_FRAG_FLAG) == END_FRAG_FLAG)
                {
                    final int msgLength = builder.limit();
                    action = delegate.onFragment(builder.buffer(), 0, msgLength, header);

                    if (Action.ABORT == action)
                    {
                        builder.limit(limit);
                    }
                    else
                    {
                        builder.reset();
                    }
                }
            }
        }

        return action;
    }
}