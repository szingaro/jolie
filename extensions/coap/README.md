# CoAP extension for the Jolie

This repository is the CoAP extension for the [Jolie Programming Language](http://www.jolie-lang.org) implementing CoAP ([RFC 7252](https://tools.ietf.org/html/rfc7252)).

## Getting Started

This guide is intended *to test this extension*, if you just want to have a running version including this protocol please refer to [http://cs.unibo.it/projects/jolie/jiot.html].

### Prerequisites

What things you need to install the software and how to install them:

* The extension currently supports project development in [NetBeans IDE](http://netbeans.org)
* [Jolie](https://github.com/jolie/jolie) version 1.6.2 - Installation instruction can be found @ http://jolie-lang.org/downloads.html
* [Netty](http://netty.io) version > 4.1.17 - Installation instruction can be found @ http://netty.io/downloads.html

### Installing

1. [Clone the Jolie Next Repository](https://github.com/stefanopiozingaro/jolie.git)
2. Import in your IDE
3. In NetBeans Project Jolie -> Properties -> Run add a new configuration with the following parameters
    * Main Class    |   jolie.Jolie
    * Configuration |   ./lib:./javaServices/*:./extensions/* -i ./include path/for/example.ol
    * Working Dir   |	../dist/jolie
    * VM Options    |   -ea:jolie... -ea:joliex... -cp ./lib/libjolie.jar:./jolie.jar

## Running the tests

First we need to define a Jolie `interface` to be used in common. Lets build a thermostat interface exposing operations for *get* and *set* temperature. Operation *core* is the operation for the CoAP Core resource list retrieval at `/.well-known/core` address.

```jolie
type TmpType: void { .id?: string } | int { .id?: string }

interface ThermostatInterface {
    OneWay: setTmp( TmpType )
    RequestResponse: 
        getTmp( TmpType )( int ),
        core( void )( string )
}
```

---

### Client Code Example

The example below shows a possible implementation of the CoAP server.

```jolie
include "console.iol"
include "thermostat.iol"

outputPort Thermostat {
    Location: "datagram://localhost:5683"
    Protocol: coap {
        .debug = true;
        .proxy = false;
        .osc.getTmp << {
            .contentFormat = "text/plain",
            .alias = "%!{id}/getTemperature",
            .messageCode = "GET",
            .messageType = "CON"
        };
        .osc.setTmp << {
            .contentFormat = "text/plain",
            .alias = "%!{id}/setTemperature",
            .messageCode = "POST",
            .messageType = "CON"
        };
        .osc.core << {
            .contentFormat = "text/plain",
            .alias = "/.well-known/core",
            .messageCode = "GET",
            .messageType = "CON"
        }
    }
    Interfaces: ThermostatInterface
}

main
{
    core@Thermostat( )( resp );
    println@Console( resp )();
    {
        println@Console( " Retrieving temperature 
        from Thermostat n.42 ... " )()
        |
        getTmp@Thermostat( { .id = "42" } )( t )
    };
    println@Console( " Thermostat n.42 forwarded temperature: " 
        + t + " C.")();
    t_confort = 21;
    if (t < t_confort) {
        println@Console( " Setting Temperature of Thermostat n.42 to " 
        + t_confort + " C ..." )()
        |
        setTmp@Thermostat( 21 { .id = "42" } );
        println@Console( " ... Thermostat set the Temperature 
        accordingly!" )()
    }
}

```

### Server Code Example

Lets show the server deployment of `inputPort Thermostat` and the behaviour for each invoked `operation`. 

```jolie
include "console.iol"
include "thermostat.iol"

inputPort  Thermostat {
    Location: "datagram://localhost:5683"
    Protocol: coap {
        .debug = true;
        .proxy = false;
        .osc.getTmp << {
            .contentFormat = "text/plain",
            .messageCode = "CONTENT",
            .alias = "42/getTemperature"
        };
        .osc.setTmp << {
            .contentFormat = "text/plain",
            .alias = "42/setTemperature"
        };
        .osc.core << {
            .contentFormat = "text/plain",
            .messageCode = "205",
            .alias = "/.well-known/core"
        }
    }
    Interfaces: ThermostatInterface
}

main 
{
    [
        getTmp( temp )( resp ) 
        {
            resp = 19;
            println@Console( " Setting Temperature of the Thermostat to " 
                + temperatura )()   
        }
    ]
    |
    [
        setTmp( temperatura )
    ] 
    {
        println@Console( " Get Temperature Request. Forwarding: " 
            + resp + " C")()
    }
    |
    [
        core(  )( response )
        {
            response = 
            "
            </getTmp>;
                obs;
                rt=\"observe\";
                title=\"Resource for retrieving of the thermostat temperature\",
            </setTmp>;
                title=\"Resource for setting temperature\"
            "
        }
    ]
}   
```

## Deployment

Add additional notes about how to deploy this on a live system

## Built With

The Jolie project and this module has been built with [Apache ant](https://ant.apache.org). 
It has been tested the build with maven too, simply create a POM file in the root directory in order to 
add your needed dependencies.

## Contributing

* [Okleine nCoap](https://github.com/okleine/nCoAP)

## Versioning
 
1.0

## Authors

* [Professor Maurizio Gabbrielli](http://cs.unibo.it/~gabbri)
* [Post Doc Saverio Giallorenzo](http://cs.unibo.it/~sgiallor)
* [Professor Ivan Lanese](http://cs.unibo.it/~lanese)
* [PhD Candidate Stefano Pio Zingaro](http://cs.unibo.it/~stefanopio.zingaro) 

## License

Please refer to [Jolie License](https://github.com/stefanopiozingaro/jolie/blob/next/LICENSE)

## Acknowledgments

* The [paper](http://cs.unibo.it/~sgiallor/publications/hicss2018/hicss2018.pdf) - Maurizio Gabbrielli, Saverio Giallorenzo, Ivan Lanese, and Stefano Pio Zingaro: A Language-based Approach for Interoperability of IoT Platforms. Hawaii International Conference on System Sciences 2018, IEEE Computer Society 2018.
* More on the jIoT (Jolie for IoT) page project @ http://cs.unibo.it/projects/jolie/jiot.html
* More on me @ http://cs.unibo.it/~stefanopio.zingaro
