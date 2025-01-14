/* jcifs msrpc client library in Java
 * Copyright (C) 2006  "Michael B. Allen" <jcifs at samba dot org>
 *                     "Eric Glass" <jcifs at samba dot org>
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package jcifs.dcerpc;

import java.io.*;
import java.net.*;
import java.security.Principal;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.util.Hexdump;
import jcifs.dcerpc.ndr.NdrBuffer;

public abstract class DcerpcHandle implements DcerpcConstants {

	/*
	 * Bindings are in the form: proto:\\server[key1=val1,key2=val2] or
	 * proto:server[key1=val1,key2=val2] or proto:[key1=val1,key2=val2]
	 * 
	 * If a key is absent it is assumed to be 'endpoint'. Thus the following are
	 * equivalent: proto:\\ts0.win.net[endpoint=\pipe\srvsvc]
	 * proto:ts0.win.net[\pipe\srvsvc]
	 * 
	 * If the server is absent it is set to "127.0.0.1"
	 */
	protected static DcerpcBinding parseBinding(String str)
			throws DcerpcException {
		int state, mark, si;
		char[] arr = str.toCharArray();
		String proto = null, key = null;
		DcerpcBinding binding = null;

		state = mark = si = 0;
		do {
			char ch = arr[si];

			switch (state) {
			case 0:
				if (ch == ':') {
					proto = str.substring(mark, si);
					mark = si + 1;
					state = 1;
				}
				break;
			case 1:
				if (ch == '\\') {
					mark = si + 1;
					break;
				}
				state = 2;
			case 2:
				if (ch == '[') {
					String server = str.substring(mark, si).trim();
					if (server.length() == 0)
						server = "127.0.0.1";
					binding = new DcerpcBinding(proto, str.substring(mark, si));
					mark = si + 1;
					state = 5;
				}
				break;
			case 5:
				if (ch == '=') {
					key = str.substring(mark, si).trim();
					mark = si + 1;
				} else if (ch == ',' || ch == ']') {
					String val = str.substring(mark, si).trim();
					if (key == null)
						key = "endpoint";
					binding.setOption(key, val);
					key = null;
				}
				break;
			default:
				si = arr.length;
			}

			si++;
		} while (si < arr.length);

		if (binding == null || binding.endpoint == null)
			throw new DcerpcException("Invalid binding URL: " + str);

		return binding;
	}

	protected DcerpcBinding binding;
	protected int max_xmit = 4280;
	protected int max_recv = max_xmit;
	protected int state = 0;
	private static int call_id = 1;

	public static DcerpcHandle getHandle(String url,
			NtlmPasswordAuthentication auth) throws UnknownHostException,
			MalformedURLException, DcerpcException {
		if (url.startsWith("ncacn_np:")) {
			return new DcerpcPipeHandle(url, auth);
		}
		throw new DcerpcException("DCERPC transport not supported: " + url);
	}

	public void sendrecv(DcerpcMessage msg) throws DcerpcException, IOException {
		byte[] stub, frag;
		NdrBuffer buf, fbuf;
		boolean isLast, isDirect;
		DcerpcException de;

		if (state == 0) {
			state = 1;
			DcerpcMessage bind = new DcerpcBind(binding, this);
			sendrecv(bind);
		}

		isDirect = msg instanceof DcerpcBind;

		try {
			stub = jcifs.smb.BufferCache.getBuffer();
		} catch (InterruptedException ie) {
			throw new IOException(ie.getMessage());
		}
		try {
			int off, tot, n;

			buf = new NdrBuffer(stub, 0);

			msg.flags = DCERPC_FIRST_FRAG | DCERPC_LAST_FRAG;
			msg.call_id = call_id;

			msg.encode(buf);

			tot = buf.getLength();
			off = 0;
			while (off < tot) {
				msg.call_id = call_id++;

				if ((tot - off) > max_xmit) {
					/*
					 * Multiple fragments. Need to set flags and length and
					 * re-encode header msg.length = n = max_xmit; msg.flags &=
					 * ~DCERPC_LAST_FRAG; buf.start = off; buf.reset();
					 * msg.encode_header(buf);
					 */
					throw new DcerpcException(
							"Fragmented request PDUs currently not supported");
				} else {
					n = tot - off;
				}

				doSendFragment(stub, off, n, isDirect);
				off += n;
			}

			doReceiveFragment(stub, isDirect);
			buf.reset();
			msg.decode_header(buf);

			off = 24;
			if (msg.ptype == 2 && msg.isFlagSet(DCERPC_LAST_FRAG) == false)
				off = msg.length;

			frag = null;
			fbuf = null;
			while (msg.isFlagSet(DCERPC_LAST_FRAG) == false) {
				int stub_frag_len;

				if (frag == null) {
					frag = new byte[max_recv];
					fbuf = new NdrBuffer(frag, 0);
				}

				doReceiveFragment(frag, isDirect);
				fbuf.reset();
				msg.decode_header(fbuf);
				stub_frag_len = msg.length - 24;

				if ((off + stub_frag_len) > stub.length) {
					// shouldn't happen if alloc_hint is correct or greater
					byte[] tmp = new byte[off + stub_frag_len];
					System.arraycopy(stub, 0, tmp, 0, off);
					stub = tmp;
				}

				System.arraycopy(frag, 24, stub, off, stub_frag_len);
				off += stub_frag_len;
			}

			buf = new NdrBuffer(stub, 0);
			msg.decode(buf);
		} finally {
			jcifs.smb.BufferCache.releaseBuffer(stub);
		}

		if ((de = msg.getResult()) != null)
			throw de;
	}

	public String getServer() {
		if (this instanceof DcerpcPipeHandle)
			return ((DcerpcPipeHandle) this).pipe.getServer();
		return null;
	}

	public Principal getPrincipal() {
		if (this instanceof DcerpcPipeHandle)
			return ((DcerpcPipeHandle) this).pipe.getPrincipal();
		return null;
	}

	public String toString() {
		return binding.toString();
	}

	protected abstract void doSendFragment(byte[] buf, int off, int length,
			boolean isDirect) throws IOException;

	protected abstract void doReceiveFragment(byte[] buf, boolean isDirect)
			throws IOException;

	public abstract void close() throws IOException;
}
