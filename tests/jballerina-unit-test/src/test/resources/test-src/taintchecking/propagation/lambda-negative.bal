// Copyright (c) 2019 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

public function secureFunction (@untainted string secureIn, string insecureIn) {
    string data = secureIn + insecureIn;
}

public function main (string... args) {
    function (string, string) returns (string) testLambda =
    function (string x, string y) returns (string) {
        string r = x + y;
        return r;
    };

    secureFunction(testLambda.call(args[0], args[0]), testLambda.call(args[0], args[0]));
}
