/***************************************************************************
 *   Copyright (C) 2006-2016 by Fabrizio Montesi <famontesi@gmail.com>     *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU Library General Public License as       *
 *   published by the Free Software Foundation; either version 2 of the    *
 *   License, or (at your option) any later version.                       *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU Library General Public     *
 *   License along with this program; if not, write to the                 *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 *                                                                         *
 *   For details about the authors of this software, see the AUTHORS file. *
 ***************************************************************************/
package jolie.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageCodec;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import jolie.net.protocols.AsyncCommProtocol;
import jolie.runtime.ByteArray;
import jolie.runtime.FaultException;
import jolie.runtime.Value;
import jolie.runtime.ValueVector;
import jolie.runtime.VariablePath;

public class SodepProtocol extends AsyncCommProtocol {

	private Charset stringCharset = Charset.forName( "UTF8" );
	//private final Interpreter interpreter;
	//private final boolean inInputport;

	private static class DataTypeHeaderId {

		private static final int NULL = 0;
		private static final int STRING = 1;
		private static final int INT = 2;
		private static final int DOUBLE = 3;
		private static final int BYTE_ARRAY = 4;
		private static final int BOOL = 5;
		private static final int LONG = 6;
	}

	public SodepProtocol( VariablePath configurationPath /*, Interpreter interpreter, boolean inInputport*/ ) {
		super( configurationPath );
		//this.interpreter = interpreter;
		//this.inInputport = inInputport;
	}

	@Override
	public String name() {
		return "sodep";
	}

	@Override
	public final boolean isThreadSafe() {
		return true && checkBooleanParameter( "keepAlive", true ); // if the channel is set to be closed, then it is not threadSafe
	}

	@Override
	public void setupPipeline( ChannelPipeline pipeline ) {
		pipeline.addLast( new SodepCommMessageCodec() );
	}

	public class SodepCommMessageCodec extends ByteToMessageCodec<CommMessage> {

		@Override
		protected void encode( ChannelHandlerContext ctx, CommMessage in, ByteBuf out ) throws Exception {

			setSendExecutionThread( in.id() );
			channel().setToBeClosed( !checkBooleanParameter( "keepAlive", true ) );
			updateCharset();
			writeMessage( out, in );
		}

		@Override
		protected void decode( ChannelHandlerContext ctx, ByteBuf in, List<Object> out ) throws Exception {
			CommMessage msg;
			in.markReaderIndex();
			try {
				msg = readMessage( in );
				channel().setToBeClosed( !checkBooleanParameter( "keepAlive", true ) );
				out.add( msg );
			} catch ( IndexOutOfBoundsException e ) {
				/*
                * Not enough bytes were available. Reset the buffer.
                * 
                * TODO instead of throwing away already read bytes and partial 
                * CommMessage, we could store the partial CommMessage, and 
                * resume decoding when more bytes are available until the 
                * complete message is read.
				 */
				in.resetReaderIndex();
			}
		}

	}

	private void updateCharset() {
		String charset = getStringParameter( "charset" );
		if ( !charset.isEmpty() ) {
			stringCharset = Charset.forName( charset );
		}
	}

	private String readString( ByteBuf in )
		throws IndexOutOfBoundsException {
		int len = in.readInt();
		if ( len > 0 ) {
			byte[] bb = new byte[ len ];
			in.readBytes( bb, 0, len );
			return new String( bb, stringCharset );
		}
		return "";
	}

	private void writeString( ByteBuf out, String str ) {
		if ( str.isEmpty() ) {
			out.writeInt( 0 );
		} else {
			byte[] bytes = str.getBytes( stringCharset );
			out.writeInt( bytes.length );
			out.writeBytes( bytes );
		}
	}

	private ByteArray readByteArray( ByteBuf in )
		throws IndexOutOfBoundsException {
		int size = in.readInt();
		ByteArray ret;
		if ( size > 0 ) {
			byte[] bytes = new byte[ size ];
			in.readBytes( bytes, 0, size );
			ret = new ByteArray( bytes );
		} else {
			ret = new ByteArray( new byte[ 0 ] );
		}
		return ret;
	}

	private void writeByteArray( ByteBuf out, ByteArray byteArray ) {
		int size = byteArray.size();
		out.writeInt( size );
		if ( size > 0 ) {
			out.writeBytes( byteArray.getBytes() );
		}
	}

	private void writeFault( ByteBuf out, FaultException fault ) {
		writeString( out, fault.faultName() );
		writeValue( out, fault.value() );
	}

	private void writeValue( ByteBuf out, Value value ) {
		Object valueObject = value.valueObject();
		if ( valueObject == null ) {
			out.writeByte( DataTypeHeaderId.NULL );
		} else if ( valueObject instanceof String ) {
			out.writeByte( DataTypeHeaderId.STRING );
			writeString( out, ( String ) valueObject );
		} else if ( valueObject instanceof Integer ) {
			out.writeByte( DataTypeHeaderId.INT );
			out.writeInt( ( Integer ) valueObject );
		} else if ( valueObject instanceof Double ) {
			out.writeByte( DataTypeHeaderId.DOUBLE );
			out.writeDouble( ( Double ) valueObject );
		} else if ( valueObject instanceof ByteArray ) {
			out.writeByte( DataTypeHeaderId.BYTE_ARRAY );
			writeByteArray( out, ( ByteArray ) valueObject );
		} else if ( valueObject instanceof Boolean ) {
			out.writeByte( DataTypeHeaderId.BOOL );
			out.writeBoolean( ( Boolean ) valueObject );
		} else if ( valueObject instanceof Long ) {
			out.writeByte( DataTypeHeaderId.LONG );
			out.writeLong( ( Long ) valueObject );
		} else {
			out.writeByte( DataTypeHeaderId.NULL );
		}

		Map< String, ValueVector> children = value.children();
		List< Entry< String, ValueVector>> entries
			= new LinkedList< Entry< String, ValueVector>>();
		for ( Entry< String, ValueVector> entry : children.entrySet() ) {
			entries.add( entry );
		}

		out.writeInt( entries.size() );
		for ( Entry< String, ValueVector> entry : entries ) {
			writeString( out, entry.getKey() );
			out.writeInt( entry.getValue().size() );
			for ( Value v : entry.getValue() ) {
				writeValue( out, v );
			}
		}
	}

	private void writeMessage( ByteBuf out, CommMessage message ) {
		out.writeLong( message.id() );
		writeString( out, message.resourcePath() );
		writeString( out, message.operationName() );
		FaultException fault = message.fault();
		if ( fault == null ) {
			out.writeBoolean( false );
		} else {
			out.writeBoolean( true );
			writeFault( out, fault );
		}
		writeValue( out, message.value() );
	}

	private Value readValue( ByteBuf in )
		throws IndexOutOfBoundsException {
		Value value = Value.create();
		Object valueObject = null;
		byte b = in.readByte();
		switch ( b ) {
			case DataTypeHeaderId.STRING:
				valueObject = readString( in );
				break;
			case DataTypeHeaderId.INT:
				valueObject = in.readInt();
				break;
			case DataTypeHeaderId.LONG:
				valueObject = in.readLong();
				break;
			case DataTypeHeaderId.DOUBLE:
				valueObject = in.readDouble();
				break;
			case DataTypeHeaderId.BYTE_ARRAY:
				valueObject = readByteArray( in );
				break;
			case DataTypeHeaderId.BOOL:
				valueObject = in.readBoolean();
				break;
			case DataTypeHeaderId.NULL:
			default:
				break;
		}

		value.setValue( valueObject );

		Map< String, ValueVector> children = value.children();
		String s;
		int n, i, size, k;
		n = in.readInt(); // How many children?
		ValueVector vec;

		for ( i = 0; i < n; i++ ) {
			s = readString( in );
			vec = ValueVector.create();
			size = in.readInt();
			for ( k = 0; k < size; k++ ) {
				vec.add( readValue( in ) );
			}
			children.put( s, vec );
		}
		return value;
	}

	private FaultException readFault( ByteBuf in )
		throws IndexOutOfBoundsException {
		String faultName = readString( in );
		Value value = readValue( in );
		return new FaultException( faultName, value );
	}

	private CommMessage readMessage( ByteBuf in )
		throws IndexOutOfBoundsException, IOException {
		Long id = in.readLong();
		if ( this.isThreadSafe() ) {
			// then we can find the execution thread by pairing it with the message ID
			setReceiveExecutionThread( id );
		} else {
			// ACTUALLY THIS WILL NEVER BE USED IN SODEP SINCE IT'S A THREAD-SAFE PROTOCOL
			setReceiveExecutionThread( channel() );
		}

		String resourcePath = readString( in );
		String operationName = readString( in );
		FaultException fault = null;

		if ( in.readBoolean() == true ) {
			fault = readFault( in );
		}

		//StatefulContext ctx = channel().getContextFor( id, inInputport );
		updateCharset(); // <- should read the ctx 
		Value value = readValue( in );
		return new CommMessage( id, operationName, resourcePath, value, fault );
	}

	@Override
	public String getConfigurationHash()
	{
		return name();
	}
	
	

}
