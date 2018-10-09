/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.wso2.ballerinalang.compiler.bir.writer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.ballerinalang.compiler.BLangCompilerException;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRBasicBlock;
import org.wso2.ballerinalang.compiler.bir.writer.CPEntry.PackageCPEntry;
import org.wso2.ballerinalang.compiler.bir.writer.CPEntry.StringCPEntry;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Serialize BIR into a binary format.
 *
 * @since 0.980.0
 */
public class BIRBinaryWriter {
    private static final byte[] BIR_MAGIC = {(byte) 0xba, (byte) 0x10, (byte) 0xc0, (byte) 0xde};
    private static final int BIR_VERSION = 1;

    private final ConstantPool cp = new ConstantPool();
    private final BIRNode.BIRPackage birPackage;

    public BIRBinaryWriter(BIRNode.BIRPackage birPackage) {
        this.birPackage = birPackage;
    }

    public byte[] serialize() {
        ByteBuf birbuf = Unpooled.buffer();
        BIRTypeWriter typeWriter = new BIRTypeWriter(birbuf, cp);

        // Write the package details in the form of constant pool entry
        int orgCPIndex = addStringCPEntry(birPackage.org.value);
        int nameCPIndex = addStringCPEntry(birPackage.name.value);
        int versionCPIndex = addStringCPEntry(birPackage.version.value);
        int pkgIndex = cp.addCPEntry(new PackageCPEntry(orgCPIndex, nameCPIndex, versionCPIndex));
        birbuf.writeInt(pkgIndex);

        // Write functions
        writeFunctions(birbuf, typeWriter, birPackage.functions);

        // Write the constant pool entries.
        // TODO Only one constant pool is available for now. This will change in future releases
        // TODO e.g., strtab, shstrtab, rodata.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dataOut = new DataOutputStream(baos)) {
            dataOut.write(BIR_MAGIC);
            dataOut.writeInt(BIR_VERSION);
            dataOut.write(cp.serialize());
            dataOut.write(birbuf.nioBuffer().array(), 0, birbuf.nioBuffer().limit());
            return baos.toByteArray();
        } catch (IOException e) {
            throw new BLangCompilerException("failed to serialize the bir", e);
        }
    }

    // private methods

    private void writeFunctions(ByteBuf buf, BIRTypeWriter typeWriter, List<BIRNode.BIRFunction> birFunctionList) {
        buf.writeInt(birFunctionList.size());
        birFunctionList.forEach(func -> writeFunction(buf, typeWriter, func));
    }

    private void writeFunction(ByteBuf buf, BIRTypeWriter typeWriter, BIRNode.BIRFunction birFunction) {
        // Function name CP Index
        buf.writeInt(addStringCPEntry(birFunction.name.value));
        // Flags
        buf.writeInt(birFunction.flags);

        // Function type as a CP Index
        birFunction.type.accept(typeWriter);

        // Arg count
        buf.writeInt(birFunction.argsCount);

        // Workers
        writeWorkers(buf, birFunction.workers);
    }

    private void writeWorkers(ByteBuf buf, List<BIRNode.BIRWorker> birWorkerList) {
        buf.writeInt(birWorkerList.size());
        birWorkerList.forEach(worker -> writeWorker(buf, worker));
    }

    private void writeWorker(ByteBuf buf, BIRNode.BIRWorker birWorker) {
        // Worker name CP Index
        buf.writeInt(addStringCPEntry(birWorker.name.value));

        // Local variables
        buf.writeInt(birWorker.localVars.size());
        for (BIRNode.BIRVariableDcl localVar : birWorker.localVars) {
            buf.writeByte(localVar.kind.getValue());
            buf.writeInt(addStringCPEntry(localVar.type.getDesc()));
            buf.writeInt(addStringCPEntry(localVar.name.value));
        }

        // Write basic blocks
        writeBasicBlocks(buf, birWorker.basicBlocks);
    }

    private void writeBasicBlocks(ByteBuf buf, List<BIRBasicBlock> birBBList) {
        BIRInstructionWriter insWriter = new BIRInstructionWriter(buf, cp);
        insWriter.writeBBs(birBBList);
    }

    private int addStringCPEntry(String value) {
        return cp.addCPEntry(new StringCPEntry(value));
    }
}
