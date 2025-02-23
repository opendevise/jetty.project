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

package org.eclipse.jetty.cdi.tests;

import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.inject.Named;

public class FriendlyGreetings
{
    @Produces
    @Named("friendly")
    public Greetings friendly(InjectionPoint ip)
    {
        return () -> "Hello " + ip.getMember().getDeclaringClass().getSimpleName();
    }

    @Produces
    @Named("old")
    public Greetings old()
    {
        return () -> "Salutations!";
    }
}
