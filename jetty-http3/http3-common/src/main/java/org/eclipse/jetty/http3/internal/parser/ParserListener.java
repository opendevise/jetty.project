//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal.parser;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;

public interface ParserListener
{
    public default void onHeaders(long streamId, HeadersFrame frame)
    {
    }

    public default void onData(long streamId, DataFrame frame)
    {
    }

    public default void onSettings(SettingsFrame frame)
    {
    }

    public default void onStreamFailure(long streamId, int error, String reason)
    {
    }

    public default void onSessionFailure(int error, String reason)
    {
    }

    public static class Wrapper implements ParserListener
    {
        protected final ParserListener listener;

        public Wrapper(ParserListener listener)
        {
            this.listener = listener;
        }

        @Override
        public void onHeaders(long streamId, HeadersFrame frame)
        {
            listener.onHeaders(streamId, frame);
        }

        @Override
        public void onData(long streamId, DataFrame frame)
        {
            listener.onData(streamId, frame);
        }

        @Override
        public void onSettings(SettingsFrame frame)
        {
            listener.onSettings(frame);
        }

        @Override
        public void onStreamFailure(long streamId, int error, String reason)
        {
            listener.onStreamFailure(streamId, error, reason);
        }

        @Override
        public void onSessionFailure(int error, String reason)
        {
            listener.onSessionFailure(error, reason);
        }
    }
}
