/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.ballerinalang.jvm.values;

import org.ballerinalang.jvm.commons.TypeValuePair;
import org.ballerinalang.jvm.scheduling.Strand;
import org.ballerinalang.jvm.types.BType;
import org.ballerinalang.jvm.util.exceptions.BLangFreezeException;
import org.ballerinalang.jvm.util.exceptions.BLangRuntimeException;
import org.ballerinalang.jvm.util.exceptions.BallerinaException;
import org.ballerinalang.jvm.values.freeze.State;
import org.ballerinalang.jvm.values.freeze.Status;
import org.ballerinalang.jvm.values.utils.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * Interface to be implemented by all the reference types.
 * </p>
 * <p>
 * <i>Note: This is an internal API and may change in future versions.</i>
 * </p>
 * 
 * @since 0.995.0
 */
public class StringValue {

    public String value;

    public StringValue(String value) {
        this.value = value;
    }

    public StringValue(StringValue value) {
        this.value = value.value;
    }

    public StringValue concat(StringValue str) {
        return new StringValue(value.concat(str.value));
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return this.value.equals(obj);
    }

    public boolean isEmpty() {
        return this.value.isEmpty();
    }

    // TODO review below API methods and remove unwanted ones
    public int length() {
        return value.length();
    }

    public char charAt(int index) {
        return value.charAt(index);
    }

    public boolean startsWith(String prefix) {
        return value.startsWith(prefix);
    }

    public int indexOf(char str) {
        return value.indexOf(str);
    }

    public int lastIndexOf(char str) {
        return value.lastIndexOf(str);
    }

    public StringValue substring(int beginIndex, int endIndex) {
        return new StringValue(value.substring(beginIndex, endIndex));
    }

    public StringValue substring(int beginIndex) {
        return new StringValue(value.substring(beginIndex));
    }
}
