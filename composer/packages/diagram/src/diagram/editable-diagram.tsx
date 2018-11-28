import { ASTNode, ASTUtil } from "@ballerina/ast-model";
import { BallerinaAST, IBallerinaLangClient  } from "@ballerina/lang-service";
import debounce from "lodash.debounce";
import React from "react";
import { CommonDiagramProps, Diagram } from "./diagram";
import { DiagramContext, IDiagramContext } from "./diagram-context";
import { Loader } from "./loader";

const resizeDelay = 200;

export interface EdiatableDiagramProps extends CommonDiagramProps {
    docUri: string;
    langClient: IBallerinaLangClient;
}

export interface EditableDiagramState {
    ast?: ASTNode;
    editingEnabled: boolean;
}

export class EditableDiagram extends React.Component<EdiatableDiagramProps, EditableDiagramState> {

    // get default context or provided context from a parent (if any)
    public static contextType = DiagramContext;

    public state = {
        ast: undefined,
        editingEnabled: false,
    };

    private wrapperRef = React.createRef<HTMLDivElement>();

    public render() {
        const { height, width, mode, zoom } = this.props;
        const { ast } = this.state;

        const wrapperDiv = this.wrapperRef.current;
        const parentOffset = wrapperDiv ? wrapperDiv.offsetParent : undefined;
        // create props for the diagram
        const diagramProps = {
            ast,
            height: (!height && parentOffset) ? parentOffset.clientHeight : height,
            mode,
            width: (!width && parentOffset) ? parentOffset.clientWidth : width,
            zoom,
        };

        return <DiagramContext.Provider value={this.createContext()}>
                <div
                    className="diagram-wrapper"
                    style={{ width: "100%", height: "100%" }}
                    ref={this.wrapperRef}
                >
                    {!ast && <Loader />}
                    {ast && <Diagram {...diagramProps} />}
                </div>
            </DiagramContext.Provider>;
    }

    public componentWillReceiveProps(nextProps: EdiatableDiagramProps) {
        if (this.props.docUri !== nextProps.docUri) {
            this.updateAST(nextProps.docUri);
        }
    }

    public componentDidMount(): void {
        this.updateAST();
        if (window) {
            window.onresize = debounce(() => {
                this.forceUpdate();
            }, resizeDelay);
        }
        ASTUtil.onTreeModified((tree) => {
            // tslint:disable-next-line:no-console
            console.log("---------------->");
            const { langClient, docUri } = this.props;
            // tslint:disable-next-line:no-console
            console.log("---------------->", tree);
            langClient.astDidChange({
                ast: tree as BallerinaAST,
                textDocumentIdentifier: {
                    uri: docUri
                },
            });
        });
    }

    public updateAST(uri: string = this.props.docUri) {
        // invoke the parser and get the AST
        this.props.langClient.getAST({
            documentIdentifier: {
                uri,
            },
        }).then((resp) => {
            if (resp.ast) {
            this.setState({
                ast: resp.ast,
            });
            }
        }, (err) => {
            // TODO Handle the error
        });
    }

    private createContext(): IDiagramContext {
        // create context contributions for the children
        const contextContributions = {
            editingEnabled: this.state.editingEnabled,
            langClient: this.props.langClient,
            toggleEditing: () => {
                this.setState({
                    editingEnabled: !this.state.editingEnabled,
                });
            }
        };

        // merge with parent (if any) or with default context
        return { ...this.context, ...contextContributions };
    }
}
