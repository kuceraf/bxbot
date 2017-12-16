# Prikazy
##### Assembly
`mvnw clean assembly:assembly -Dmaven.test.skip=true`

## AWS
##### Deploy eb
`eb deploy bxbot-dev --staged`
##### Zapnout cloud watch 
`eb logs -cw enable`