//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack.internal.instruction;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class StreamCancellationInstruction extends AbstractInstruction
{
    private final long _streamId;

    public StreamCancellationInstruction(ByteBufferPool bufferPool, long streamId)
    {
        super(bufferPool);
        _streamId = streamId;
    }

    @Override
    public void encode(ByteBufferPool.Accumulator accumulator)
    {
        int size = NBitIntegerEncoder.octetsNeeded(6, _streamId) + 1;
        RetainableByteBuffer buffer = getByteBufferPool().acquire(size, false);
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        BufferUtil.clearToFill(byteBuffer);
        byteBuffer.put((byte)0x40);
        NBitIntegerEncoder.encode(byteBuffer, 6, _streamId);
        BufferUtil.flipToFlush(byteBuffer, 0);
        accumulator.append(buffer);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
