/***************************************************************************
 *   Copyright (C) by Fabrizio Montesi                                     *
 *   Copyright (C) by Mauro Silvagni                                       *
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.Name;
import javax.xml.soap.SOAPBody;
import javax.xml.soap.SOAPBodyElement;
import javax.xml.soap.SOAPConstants;
import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPEnvelope;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import jolie.Interpreter;
import jolie.net.http.HTTPMessage;
import jolie.net.http.HTTPParser;
import jolie.runtime.InputOperation;
import jolie.runtime.InvalidIdException;
import jolie.runtime.Value;
import jolie.runtime.ValueVector;
import jolie.runtime.VariablePath;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.sun.xml.xsom.XSAttributeDecl;
import com.sun.xml.xsom.XSAttributeUse;
import com.sun.xml.xsom.XSComplexType;
import com.sun.xml.xsom.XSContentType;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSModelGroup;
import com.sun.xml.xsom.XSModelGroupDecl;
import com.sun.xml.xsom.XSParticle;
import com.sun.xml.xsom.XSSchema;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSTerm;
import com.sun.xml.xsom.XSType;
import com.sun.xml.xsom.parser.XSOMParser;

/** Implements the SOAP over HTTP protocol.
 * 
 * @author Fabrizio Montesi
 * 
 * 2006 - Initially written by Fabrizio Montesi and Mauro Silvagni
 * 2007 - Totally re-built by Fabrizio Montesi, exploiting new JOLIE capabilities
 * 
 */
public class SOAPProtocol extends CommProtocol
{
	private String inputId = null;
	private Interpreter interpreter;
	private MessageFactory messageFactory;
	private XSSchemaSet schemaSet = null;
	private VariablePath locationVariablePath;
	private URI uri = null;

	public SOAPProtocol clone()
	{
		SOAPProtocol ret = new SOAPProtocol( configurationPath );
		ret.locationVariablePath = locationVariablePath;
		ret.interpreter = interpreter;
		ret.messageFactory = messageFactory;
		ret.schemaSet = schemaSet;
		return ret;
	}
	
	private SOAPProtocol( VariablePath configurationPath )
	{
		super( configurationPath );
	}
	
	public SOAPProtocol( VariablePath configurationPath, URI uri, Interpreter interpreter )
	{
		super( configurationPath );
		this.uri = uri;
		this.interpreter = interpreter;
		try {
			this.messageFactory = MessageFactory.newInstance( SOAPConstants.SOAP_1_1_PROTOCOL );
		} catch( SOAPException e ) {
			interpreter.logger().severe( e.getMessage() );
		}
	}

	public SOAPProtocol( VariablePath configurationPath, VariablePath locationVariablePath, Interpreter interpreter )
	{
		super( configurationPath );
		this.locationVariablePath = locationVariablePath;
		this.interpreter = interpreter;
		try {
			this.messageFactory = MessageFactory.newInstance( SOAPConstants.SOAP_1_1_PROTOCOL );
		} catch( SOAPException e ) {
			interpreter.logger().severe( e.getMessage() );
		}
	}
	
	private Map< String, String > namespacePrefixMap = new HashMap< String, String > ();
	
	private XSSchemaSet getSchemaSet()
		throws IOException, SAXException
	{
		if ( schemaSet == null ) {
			ValueVector vec = getParameterVector( "schema" );
			if ( vec.size() > 0 ) {
				XSOMParser schemaParser = new XSOMParser();
				for( Value v : vec )
					schemaParser.parse( new File( v.strValue() ) );
				schemaSet = schemaParser.getResult();
				String nsPrefix = "jolie";
				int i = 1;
				for( XSSchema schema : schemaSet.getSchemas() ) {
					if ( !schema.getTargetNamespace().equals( XMLConstants.W3C_XML_SCHEMA_NS_URI ) )
						namespacePrefixMap.put( schema.getTargetNamespace(), nsPrefix + i++ );
				}
			}
		}

		return schemaSet;
	}
	
	private void initNamespacePrefixes( SOAPElement element )
		throws SOAPException
	{
		for( Entry< String, String > entry : namespacePrefixMap.entrySet() )
			element.addNamespaceDeclaration( entry.getValue(), entry.getKey() );
	}
	
	private static void valueToSOAPBody(
			Value value,
			SOAPElement element,
			SOAPEnvelope soapEnvelope
			)
		throws SOAPException
	{
		//String type = null;
		SOAPElement currentElement;
		if ( value.isDefined() ) {
			/*if ( value.isInt() )
				type = "int";
			else
				type = "string";
			element.addAttribute( soapEnvelope.createName( "type" ), "xsd:" + type );*/
			element.addTextNode( value.strValue() );
		}

		for( Entry< String, ValueVector > entry : value.children().entrySet() ) {
			for( Value val : entry.getValue() ) {
				currentElement = element.addChildElement( entry.getKey() );
				for( Entry< String, Value > attrEntry : val.attributes().entrySet() ) {
					currentElement.addAttribute(
							soapEnvelope.createName( attrEntry.getKey() ),
							attrEntry.getValue().strValue()
							);
				}
				valueToSOAPBody( val, currentElement, soapEnvelope );
			}
		}
	}
	
	private String getPrefixOrNull( XSAttributeDecl decl )
	{
		if ( decl.getOwnerSchema().attributeFormDefault() )
			return namespacePrefixMap.get( decl.getOwnerSchema().getTargetNamespace() );
		return null;
	}
	
	private String getPrefixOrNull( XSElementDecl decl )
	{
		if ( decl.getOwnerSchema().elementFormDefault() )
			return namespacePrefixMap.get( decl.getOwnerSchema().getTargetNamespace() );
		return null;
	}
	
	private void valueToTypedSOAP(
			Value value,
			XSElementDecl xsDecl,
			SOAPElement element,
			SOAPEnvelope envelope
			)
		throws SOAPException
	{
		XSType type = xsDecl.getType();
		
		if ( type.isSimpleType() ) {
			element.addTextNode( value.strValue() );
		} else if ( type.isComplexType() ) {
			String name;
			Value currValue;
			XSComplexType complexT = type.asComplexType();

			// Iterate over attributes
			Collection< ? extends XSAttributeUse > attributeUses = complexT.getAttributeUses();
			for( XSAttributeUse attrUse : attributeUses ) {
				name = attrUse.getDecl().getName();
				if ( (currValue=value.attributes().get( name )) != null ) {
					QName attrName = envelope.createQName( name, getPrefixOrNull( attrUse.getDecl() ) );
					element.addAttribute( attrName, currValue.strValue() );
				}
			}
			
			
			XSParticle particle;
			XSContentType contentT;
			contentT = complexT.getContentType();
			if ( contentT.asSimpleType() != null )
				element.addTextNode( value.strValue() );
			else if ( (particle=contentT.asParticle()) != null ) {
				XSTerm term = particle.getTerm();
//				XSElementDecl elementDecl;
				XSModelGroupDecl modelGroupDecl;
				XSModelGroup modelGroup = null;
				//int size = value.children().size();
				//if ( particle.getMinOccurs()
				// It's a simple element, repeated some times
				/*if ( (elementDecl=term.asElementDecl()) != null ) {

				} else */if ( (modelGroupDecl=term.asModelGroupDecl()) != null ) {
					modelGroup = modelGroupDecl.getModelGroup();
				} else if ( term.isModelGroup() )
					modelGroup = term.asModelGroup();
				
				if ( modelGroup != null ) {
					XSModelGroup.Compositor compositor = modelGroup.getCompositor();
					if ( compositor.equals( XSModelGroup.SEQUENCE ) ) {
						XSParticle[] children = modelGroup.getChildren();
						XSTerm currTerm;
						XSElementDecl currElementDecl;
						Value v;
						ValueVector vec;
						String prefix;
						for( int i = 0; i < children.length; i++ ) {
							currTerm = children[i].getTerm();
							if ( currTerm.isElementDecl() ) {
								currElementDecl = currTerm.asElementDecl();
								name = currElementDecl.getName();
								prefix = getPrefixOrNull( currElementDecl );
								SOAPElement childElement = null;
								if ( prefix == null )
									childElement = element.addChildElement( name );
								else
									childElement = element.addChildElement( name, prefix );
								if ( (vec=value.children().get( name )) != null ) {
									v = vec.remove( 0 );
									valueToTypedSOAP(
										v,
										currElementDecl,
										childElement,
										envelope );
								} else if ( children[i].getMinOccurs() > 0 ) {
									// TODO improve this error message.
									throw new SOAPException( "Invalid variable structure: expected " + name );
								}
							}
						}
					}
				}
			}
		}
	}
	
	private URI getURI()
		throws URISyntaxException
	{
		if ( uri == null )
			return new URI( locationVariablePath.getValue().strValue() );
		return uri;
	}
	
	public void send( OutputStream ostream, CommMessage message )
		throws IOException
	{		
		try {
			String inputId = message.inputId();
			try {
				interpreter.getRequestResponseOperation( inputId );
				
				// We're responding to a request
				inputId += "Response";
			} catch( InvalidIdException iie ) {}

			String messageNamespace = getParameterVector( "namespace" ).first().strValue();
			SOAPMessage soapMessage = messageFactory.createMessage();
			SOAPEnvelope soapEnvelope = soapMessage.getSOAPPart().getEnvelope();
			SOAPBody soapBody = soapEnvelope.getBody();
			
			XSSchemaSet schemaSet = getSchemaSet();
			XSElementDecl elementDecl;
			if ( schemaSet == null ||
					(elementDecl=schemaSet.getElementDecl( messageNamespace, inputId )) == null
					) {
				Name operationName = soapEnvelope.createName( inputId );
				SOAPBodyElement opBody = soapBody.addBodyElement( operationName );
				valueToSOAPBody( message.value(), opBody, soapEnvelope );
			} else {
				initNamespacePrefixes( soapEnvelope );
				Name operationName = null;
				if ( elementDecl.getOwnerSchema().elementFormDefault() )
					operationName = soapEnvelope.createName( inputId, namespacePrefixMap.get( elementDecl.getOwnerSchema().getTargetNamespace() ), null );
				else
					operationName = soapEnvelope.createName( inputId );
							
				SOAPBodyElement opBody = soapBody.addBodyElement( operationName );
				valueToTypedSOAP( message.value(), elementDecl, opBody, soapEnvelope );
			}

			ByteArrayOutputStream tmpStream = new ByteArrayOutputStream();
			soapMessage.writeTo( tmpStream );
			
			String soapString = "\n<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
								new String( tmpStream.toByteArray() );

			String messageString = new String();
			String soapAction = null;
			InputOperation operation = null;
			
			try {
				operation = interpreter.getRequestResponseOperation( message.inputId() );
			} catch( InvalidIdException iie ) {}
			if ( operation != null ) {
				// We're responding to a request
				messageString += "HTTP/1.1 200 OK\r\n";
			} else {
				// We're sending a notification or a solicit
				String path = getURI().getPath();
				if ( path == null || path.length() == 0 )
					path = "*";
				messageString += "POST " + path + " HTTP/1.1\r\n";
				messageString += "Host: " + getURI().getHost() + "\r\n";
				soapAction =
					"SOAPAction: \"" + messageNamespace + "/" + message.inputId() + "\"\r\n";
			}
			
			if ( getParameterVector( "keepAlive" ).first().intValue() != 1 ) {
				channel.setToBeClosed( true );
				messageString += "Connection: close\r\n";
			}
			
			//messageString += "Content-Type: application/soap+xml; charset=\"utf-8\"\n";
			messageString += "Content-Type: text/xml; charset=\"utf-8\"\r\n";
			messageString += "Content-Length: " + soapString.length() + "\r\n";
			if ( soapAction != null )
				messageString += soapAction;
			messageString += soapString + "\r\n";
			
			if ( getParameterVector( "debug" ).first().intValue() > 0 )
				interpreter.logger().info( "[SOAP debug] Sending:\n" + tmpStream.toString() );

			inputId = message.inputId();
			
			Writer writer = new OutputStreamWriter( ostream );
			writer.write( messageString );
			writer.flush();
		} catch( SOAPException se ) {
			throw new IOException( se );
		} catch( SAXException saxe ) {
			throw new IOException( saxe );
		} catch( URISyntaxException urie ) {
			throw new IOException( urie );
		}
	}
	
	private static void xmlNodeToValue( Value value, Node node )
	{
		Node currNode;
		
		// Set attributes
		NamedNodeMap attributes = node.getAttributes();
		if ( attributes != null ) {
			for( int i = 0; i < attributes.getLength(); i++ ) {
				currNode = attributes.item( i );
				value.getAttribute( currNode.getNodeName() ).setValue( currNode.getNodeValue() );
			}
		}
		
		// Set children
		NodeList list = node.getChildNodes();
		Value childValue;
		for( int i = 0; i < list.getLength(); i++ ) {
			currNode = list.item( i );
			switch( currNode.getNodeType() ) {
			case Node.ELEMENT_NODE:
				childValue = value.getNewChild( currNode.getLocalName() );
				xmlNodeToValue( childValue, currNode );
				break;
			case Node.TEXT_NODE:
				value.setValue( currNode.getNodeValue() );
				break;
			}
		}
		
		Value attr;
		if ( (attr=value.attributes().get( "type" )) != null ) {
			String type = attr.strValue();
			if ( "xsd:int".equals( type ) )
				value.setValue( value.intValue() );
			else if ( "xsd:double".equals( type ) )
				value.setValue( value.doubleValue() );
			else if ( "xsd:string".equals( type ) )
				value.setValue( value.strValue() );
		}
	}
	
	public CommMessage recv( InputStream istream )
		throws IOException
	{
		HTTPParser parser = new HTTPParser( istream );
		HTTPMessage message = parser.parse();
		
		HTTPMessage.Version version = message.version();
		if ( version == null || version.equals( HTTPMessage.Version.HTTP_1_1 ) ) {
			// The default is to keep the connection open, unless Connection: close is specified
			if ( message.getPropertyOrEmptyString( "connection" ).equalsIgnoreCase( "close" ) )
				channel.setToBeClosed( true );
			else
				channel.setToBeClosed( false );
		} else if ( version.equals( HTTPMessage.Version.HTTP_1_0 ) ) {
			// The default is to close the connection, unless Connection: Keep-Alive is specified
			if ( message.getPropertyOrEmptyString( "connection" ).equalsIgnoreCase( "keep-alive" ) )
				channel.setToBeClosed( false );
			else
				channel.setToBeClosed( true );
		}
		
		CommMessage retVal = null;
		
		try {
			SOAPMessage soapMessage = messageFactory.createMessage();
			
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware( true );
			DocumentBuilder builder = factory.newDocumentBuilder();

			InputSource src = new InputSource( new ByteArrayInputStream( message.content() ) );
			Document doc = builder.parse( src );
			DOMSource dom = new DOMSource( doc );

			soapMessage.getSOAPPart().setContent( dom );
			
			if ( getParameterVector( "debug" ).first().intValue() > 0 ) {
				ByteArrayOutputStream tmpStream = new ByteArrayOutputStream();
				soapMessage.writeTo( tmpStream );

				interpreter.logger().info( "[SOAP debug] Receiving:\n" + tmpStream.toString() );
			}

			ValueVector schemaPaths = getParameterVector( "schema" );
			if ( schemaPaths.size() > 0 ) {
				Source[] sources = new Source[ schemaPaths.size() ];
				for( int i = 0; i < schemaPaths.size(); i++ )
					sources[ i ] = new StreamSource( new File( schemaPaths.get( i ).strValue() ) );
				
				Schema schema =
					SchemaFactory.newInstance( XMLConstants.W3C_XML_SCHEMA_NS_URI ) 
							.newSchema( sources );
				schema.newValidator().validate( new DOMSource( soapMessage.getSOAPBody().getFirstChild() ) );
			}
			
			Value value = Value.create();
			xmlNodeToValue(
					value, soapMessage.getSOAPBody().getFirstChild()
					);
			
			if ( message.type() == HTTPMessage.Type.RESPONSE ) { 
				retVal = new CommMessage( inputId, value );
			} else if (
					message.type() == HTTPMessage.Type.POST ||
					message.type() == HTTPMessage.Type.GET
					) {
				retVal = new CommMessage( soapMessage.getSOAPBody().getFirstChild().getLocalName(), value );
			}
		} catch( SOAPException se ) {
			throw new IOException( se );
		} catch( ParserConfigurationException pce ) {
			throw new IOException( pce );
		} catch( SAXException saxe ) {
			throw new IOException( saxe );
		}
		
		return retVal;
	}
}