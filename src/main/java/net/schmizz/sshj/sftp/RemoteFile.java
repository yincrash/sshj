/**
 * Copyright 2009 sshj contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.schmizz.sshj.sftp;

import net.schmizz.concurrent.Promise;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.sftp.Response.StatusCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

public class RemoteFile
        extends RemoteResource {

    public RemoteFile(Requester requester, String path, byte[] handle) {
        super(requester, path, handle);
    }

    public FileAttributes fetchAttributes()
            throws IOException {
        return requester.request(newRequest(PacketType.FSTAT))
                .retrieve(requester.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .ensurePacketTypeIs(PacketType.ATTRS)
                .readFileAttributes();
    }

    public long length()
            throws IOException {
        return fetchAttributes().getSize();
    }

    public void setLength(long len)
            throws IOException {
        setAttributes(new FileAttributes.Builder().withSize(len).build());
    }

    public int read(long fileOffset, byte[] to, int offset, int len)
            throws IOException {
        final Response res = asyncRead(fileOffset, len).retrieve(requester.getTimeoutMs(), TimeUnit.MILLISECONDS);
        return checkReadResponse(res, to, offset);
    }

    protected Promise<Response, SFTPException> asyncRead(long fileOffset, int len)
            throws IOException {
        return requester.request(newRequest(PacketType.READ).putUInt64(fileOffset).putUInt32(len));
    }

    protected int checkReadResponse(Response res, byte[] to, int offset)
            throws Buffer.BufferException, SFTPException {
        switch (res.getType()) {
            case DATA:
                int recvLen = res.readUInt32AsInt();
                System.arraycopy(res.array(), res.rpos(), to, offset, recvLen);
                return recvLen;

            case STATUS:
                res.ensureStatusIs(StatusCode.EOF);
                return -1;

            default:
                throw new SFTPException("Unexpected packet: " + res.getType());
        }
    }

    public void write(long fileOffset, byte[] data, int off, int len)
            throws IOException {
        checkWriteResponse(asyncWrite(fileOffset, data, off, len));
    }

    protected Promise<Response, SFTPException> asyncWrite(long fileOffset, byte[] data, int off, int len)
            throws IOException {
        return requester.request(newRequest(PacketType.WRITE)
                                         .putUInt64(fileOffset)
                                        // TODO The SFTP spec claims this field is unneeded...? See #187
                                         .putUInt32(len)
                                         .putRawBytes(data, off, len)
        );
    }

    private void checkWriteResponse(Promise<Response, SFTPException> responsePromise)
            throws SFTPException {
        responsePromise.retrieve(requester.getTimeoutMs(), TimeUnit.MILLISECONDS).ensureStatusPacketIsOK();
    }

    public void setAttributes(FileAttributes attrs)
            throws IOException {
        requester.request(newRequest(PacketType.FSETSTAT).putFileAttributes(attrs))
                .retrieve(requester.getTimeoutMs(), TimeUnit.MILLISECONDS).ensureStatusPacketIsOK();
    }

    public int getOutgoingPacketOverhead() {
        return 1 + // packet type
                4 + // request id
                4 + // next length
                handle.length + // next
                8 + // file offset
                4 + // data length
                4; // packet length
    }

    public class RemoteFileOutputStream
            extends OutputStream {

        private final byte[] b = new byte[1];

        private final int maxUnconfirmedWrites;
        private final Queue<Promise<Response, SFTPException>> unconfirmedWrites;

        private long fileOffset;

        public RemoteFileOutputStream() {
            this(0);
        }

        public RemoteFileOutputStream(long startingOffset) {
            this(startingOffset, 0);
        }

        public RemoteFileOutputStream(long startingOffset, int maxUnconfirmedWrites) {
            this.fileOffset = startingOffset;
            this.maxUnconfirmedWrites = maxUnconfirmedWrites;
            this.unconfirmedWrites = new LinkedList<Promise<Response, SFTPException>>();
        }

        @Override
        public void write(int w)
                throws IOException {
            b[0] = (byte) w;
            write(b, 0, 1);
        }

        @Override
        public void write(byte[] buf, int off, int len)
                throws IOException {
            if (unconfirmedWrites.size() > maxUnconfirmedWrites) {
                checkWriteResponse(unconfirmedWrites.remove());
            }
            unconfirmedWrites.add(RemoteFile.this.asyncWrite(fileOffset, buf, off, len));
            fileOffset += len;
        }

        @Override
        public void flush()
                throws IOException {
            while (!unconfirmedWrites.isEmpty()) {
                checkWriteResponse(unconfirmedWrites.remove());
            }
        }

        @Override
        public void close()
                throws IOException {
            flush();
        }

    }

    public class RemoteFileInputStream
            extends InputStream {

        private final byte[] b = new byte[1];

        private long fileOffset;
        private long markPos;
        private long readLimit;

        public RemoteFileInputStream() {
            this(0);
        }

        public RemoteFileInputStream(long fileOffset) {
            this.fileOffset = fileOffset;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int readLimit) {
            this.readLimit = readLimit;
            markPos = fileOffset;
        }

        @Override
        public void reset()
                throws IOException {
            fileOffset = markPos;
        }

        @Override
        public long skip(long n)
                throws IOException {
            return (this.fileOffset = Math.min(fileOffset + n, length()));
        }

        @Override
        public int read()
                throws IOException {
            return read(b, 0, 1) == -1 ? -1 : b[0] & 0xff;
        }

        @Override
        public int read(byte[] into, int off, int len)
                throws IOException {
            int read = RemoteFile.this.read(fileOffset, into, off, len);
            if (read != -1) {
                fileOffset += read;
                if (markPos != 0 && read > readLimit) // Invalidate mark position
                    markPos = 0;
            }
            return read;
        }

    }

    public class ReadAheadRemoteFileInputStream
            extends InputStream {

        private final byte[] b = new byte[1];

        private final int maxUnconfirmedReads;
        private final Queue<Promise<Response, SFTPException>> unconfirmedReads;

        private long fileOffset;
        private boolean eof;

        public ReadAheadRemoteFileInputStream(int maxUnconfirmedReads) {
            this.maxUnconfirmedReads = maxUnconfirmedReads;
            this.unconfirmedReads = new LinkedList<Promise<Response, SFTPException>>();
            this.fileOffset = 0;
        }

        public ReadAheadRemoteFileInputStream(int maxUnconfirmedReads, long fileOffset) {
            this.maxUnconfirmedReads = maxUnconfirmedReads;
            this.unconfirmedReads = new LinkedList<Promise<Response, SFTPException>>();
            this.fileOffset = fileOffset;
        }

        @Override
        public long skip(long n)
                throws IOException {
            throw new IOException("skip is not supported by ReadAheadFileInputStream, use RemoteFileInputStream instead");
        }

        @Override
        public int read()
                throws IOException {
            return read(b, 0, 1) == -1 ? -1 : b[0] & 0xff;
        }

        @Override
        public int read(byte[] into, int off, int len)
                throws IOException {
            while (!eof && unconfirmedReads.size() <= maxUnconfirmedReads) {
                // Send read requests as long as there is no EOF and we have not reached the maximum parallelism
                unconfirmedReads.add(asyncRead(fileOffset, len));
                fileOffset += len;
            }
            if (unconfirmedReads.isEmpty()) {
                assert eof;
                return -1;
            }
            // Retrieve first in
            final Response res = unconfirmedReads.remove().retrieve(requester.getTimeoutMs(), TimeUnit.MILLISECONDS);
            final int recvLen = checkReadResponse(res, into, off);
            if (recvLen == -1) {
                eof = true;
            }
            return recvLen;
        }

    }

}