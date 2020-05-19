//
// Copyright (c) 2017 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite;

import java.util.Map;


/**
 * Authenticator is an opaque authenticator interface and not intended for application to
 * implement a custom authenticator by subclassing Authenticator interface.
 * <p>
 * NOTE: Authenticator is and abstract class (instead of an interface) so that
 * the <code>authenticate</code> method is visible only in this package.
 */
public abstract class Authenticator {
    abstract void authenticate(Map<String, Object> options);
}
