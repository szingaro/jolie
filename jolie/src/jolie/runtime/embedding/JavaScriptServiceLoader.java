/***************************************************************************
 *   Copyright (C) by Fabrizio Montesi                                     *
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

package jolie.runtime.embedding;

import java.io.FileNotFoundException;
import java.io.FileReader;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 *
 * @author Fabrizio Montesi
 */
public class JavaScriptServiceLoader extends EmbeddedServiceLoader
{
	final private Invocable invocable;

	public JavaScriptServiceLoader( String jsPath )
		throws EmbeddedServiceLoaderCreationException
	{
		ScriptEngineManager manager = new ScriptEngineManager();
		ScriptEngine engine = manager.getEngineByName( "JavaScript" );
		if ( engine == null ) {
			throw new EmbeddedServiceLoaderCreationException( "JavaScript engine not found. Check your system." );
		}

		try {
			engine.eval( new FileReader( jsPath ) );
		} catch( FileNotFoundException e ) {
			throw new EmbeddedServiceLoaderCreationException( e );
		} catch( ScriptException e ) {
			throw new EmbeddedServiceLoaderCreationException( e );
		}
		this.invocable = (Invocable)engine;
	}

	public void load()
	{
		setChannel( new JavaScriptCommChannel( invocable ) );
	}
}