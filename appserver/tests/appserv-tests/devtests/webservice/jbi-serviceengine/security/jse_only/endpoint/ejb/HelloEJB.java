/*
 * Copyright (c) 2017, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package endpoint.ejb;

import java.security.Principal;
import jakarta.jws.WebService;
import jakarta.xml.ws.WebServiceRef;
import jakarta.ejb.Stateless;
import jakarta.ejb.SessionContext;
import jakarta.annotation.Resource;

@WebService(endpointInterface="endpoint.ejb.Hello", targetNamespace="http://endpoint/ejb")
@Stateless
public class HelloEJB implements Hello {

    @Resource private SessionContext ctx;

    public String sayHello(String who) {
        System.out.println("**** sayHello("+ who+")");
        Principal p = ctx.getCallerPrincipal();
        String principal = (p == null)? "NULL": p.getName();
        System.out.println("****EJB: principal = " + principal);
        return "JBI-SecurityTest " + who + " PrincipalGot="+principal;
    }
}
