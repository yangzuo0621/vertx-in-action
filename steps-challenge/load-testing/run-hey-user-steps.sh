#!/bin/bash
echo 'NOTE: the token in this script may need to be updated'
hey -z $2 \
    -o csv \
    -H 'Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZpY2VJZCI6InAtMTIzNDU2LWFiY2RlZiIsImlhdCI6MTcxNzg0NjI3MywiZXhwIjoxNzE4NDUxMDczLCJpc3MiOiIxMGstc3RlcHMtYXBpIiwic3ViIjoibG9hZHRlc3RpbmctdXNlciJ9.b3tdi-TjYIr0fnuvspyl9ea9dHATomSP_OMEUI-Lf7zePgPSQ9hno6DWWDbnSdFORBfZKrLTiyHn1tivpSkILwNbOGyS4GUtVt_CvRYp083xsAUBSpjcoN9eMOU3Oa4PxxV1i-_96npngrzrFaMJ5maeRp2lC6vwXSCjuJs47qO_n3zX1NUeFKMNSWbPKn-8a-CxfAn9qAenYbDpH4AwXWLwEsSfhKIM3q9JnB8-qShzlLcfxxeFCN2uALa9zfH3vt1voG5hJ7moZAOPGyaaiVKYdtsQt5KarLMV600S_7gG8FDdQflXfFsxQc2VaopSyvd4fEzMck7bSQqYtVupuA' \
    http://$1:4000/api/v1/loadtesting-user/total \
    > data/hey-run-steps-z$2.csv